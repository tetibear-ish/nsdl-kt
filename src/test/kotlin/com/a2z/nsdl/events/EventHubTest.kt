package com.a2z.nsdl.events

import com.a2z.nsdl.model.EventPayload.BootCompleted
import com.a2z.nsdl.model.EventPayload.PowerOnStarted
import com.a2z.nsdl.model.ObjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventHubTest {
    private var clock = 0L
    private fun hub(retention: Int = 10_000) = EventHub(retention, now = { clock })

    @Test
    fun `emit stamps a sequence number, the current time, and the payload's own type name`() {
        val hub = hub()
        clock = 42L

        hub.emit(ObjectId("printer1"), PowerOnStarted(1))

        assertEquals(1L, hub.lastSeq)
        val sub = (hub.subscribe(EventFilter(), from = 0, capacity = 10) as SubscribeResult.Subscribed).subscription
        val record = (sub.poll() as Delivery.Event).record
        assertEquals(1L, record.seq)
        assertEquals(42L, record.timeMs)
        assertEquals(ObjectId("printer1"), record.source)
        assertEquals("PowerOnStarted", record.type)
        assertEquals(PowerOnStarted(1), record.payload)
        assertNull(record.correlationId)
    }

    @Test
    fun `a filter by object matches that object and its children, not unrelated objects`() {
        val hub = hub()
        val sub = subscribed(hub, EventFilter(objectId = ObjectId("printer1")))

        hub.emit(ObjectId("printer1"), PowerOnStarted(1))
        hub.emit(ObjectId("printer1.eth0"), PowerOnStarted(1))
        hub.emit(ObjectId("server1"), PowerOnStarted(1))

        assertEquals(
            listOf(ObjectId("printer1"), ObjectId("printer1.eth0")),
            drain(sub).map { it.source },
        )
    }

    @Test
    fun `a filter by type matches only that payload type`() {
        val hub = hub()
        val sub = subscribed(hub, EventFilter(types = setOf("BootCompleted")))

        hub.emit(ObjectId("printer1"), PowerOnStarted(1))
        hub.emit(ObjectId("printer1"), BootCompleted(1))

        assertEquals(listOf("BootCompleted"), drain(sub).map { it.type })
    }

    @Test
    fun `subscribing replays retained events with seq greater than the given cursor`() {
        val hub = hub()
        hub.emit(ObjectId("a"), PowerOnStarted(1)) // seq 1
        hub.emit(ObjectId("a"), BootCompleted(1)) // seq 2
        hub.emit(ObjectId("a"), PowerOnStarted(2)) // seq 3

        val sub = subscribed(hub, EventFilter(), from = 1)

        assertEquals(listOf(2L, 3L), drainRecords(sub).map { it.seq })
    }

    @Test
    fun `subscribing with a cursor older than the retention ring is rejected as CursorExpired`() {
        val hub = hub(retention = 2)
        hub.emit(ObjectId("a"), PowerOnStarted(1)) // seq 1, evicted
        hub.emit(ObjectId("a"), PowerOnStarted(2)) // seq 2
        hub.emit(ObjectId("a"), PowerOnStarted(3)) // seq 3 -- ring now holds [2, 3]

        assertEquals(SubscribeResult.CursorExpired, hub.subscribe(EventFilter(), from = 0, capacity = 10))
        // from = 1 (the seq right before the oldest retained one) is still fully replayable.
        assertTrue(hub.subscribe(EventFilter(), from = 1, capacity = 10) is SubscribeResult.Subscribed)
    }

    @Test
    fun `a full queue overflows into a single Gap and closes, without affecting other subscribers`() {
        val hub = hub()
        val small = subscribed(hub, EventFilter(), capacity = 2)
        val roomy = subscribed(hub, EventFilter(), capacity = 10)

        repeat(5) { hub.emit(ObjectId("a"), PowerOnStarted(it.toLong())) }

        assertTrue(small.isClosed)
        val smallDeliveries = generateSequence { small.poll() }.toList()
        assertEquals(listOf(Delivery.Gap(0L)), smallDeliveries, "queue was cleared and replaced by one Gap")

        assertTrue(!roomy.isClosed)
        assertEquals(5, drain(roomy).size, "unaffected by the other subscription overflowing")
    }

    @Test
    fun `a subscriber that never polls does not block or fail publishing`() {
        val hub = hub()
        val sub = subscribed(hub, EventFilter(), capacity = 1)

        repeat(1000) { hub.emit(ObjectId("a"), PowerOnStarted(it.toLong())) }

        assertEquals(1000L, hub.lastSeq, "publishing never blocked despite an unpolled, overflowing subscriber")
        assertTrue(sub.isClosed)
    }

    @Test
    fun `a closed subscription receives nothing further, whether closed by overflow or explicitly`() {
        val hub = hub()
        val overflowed = subscribed(hub, EventFilter(), capacity = 1)
        repeat(3) { hub.emit(ObjectId("a"), PowerOnStarted(it.toLong())) }
        drain(overflowed) // consume the Gap
        hub.emit(ObjectId("a"), PowerOnStarted(99))
        assertNull(overflowed.poll(), "closed by overflow: nothing more is queued")

        val explicit = subscribed(hub, EventFilter())
        explicit.close()
        hub.emit(ObjectId("a"), PowerOnStarted(100))
        assertNull(explicit.poll(), "closed explicitly: nothing more is queued")
    }

    private fun subscribed(hub: EventHub, filter: EventFilter, from: Long = hub.lastSeq, capacity: Int = 100): Subscription =
        (hub.subscribe(filter, from, capacity) as SubscribeResult.Subscribed).subscription

    private fun drainRecords(sub: Subscription): List<EventRecord> =
        generateSequence { sub.poll() }.filterIsInstance<Delivery.Event>().map { it.record }.toList()

    private fun drain(sub: Subscription) = drainRecords(sub)
}
