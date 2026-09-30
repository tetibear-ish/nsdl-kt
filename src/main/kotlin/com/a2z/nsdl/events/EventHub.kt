package com.a2z.nsdl.events

import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.ObjectId

/** A stamped, sequenced event. [correlationId] is null until something (a future runtime) attaches one. */
data class EventRecord(
    val seq: Long,
    val timeMs: Long,
    val source: ObjectId,
    val type: String,
    val payload: EventPayload,
    val correlationId: String? = null,
)

/** Matches events by source object (children included, e.g. "printer1" matches "printer1.eth0") and/or type. */
data class EventFilter(val objectId: ObjectId? = null, val types: Set<String>? = null) {
    fun matches(record: EventRecord): Boolean {
        if (objectId != null && record.source != objectId && !record.source.value.startsWith("${objectId.value}.")) return false
        if (types != null && record.type !in types) return false
        return true
    }
}

sealed interface Delivery {
    data class Event(val record: EventRecord) : Delivery
    /** The subscriber fell behind past this subscription's capacity; it is now closed. */
    data class Gap(val lastDeliveredSeq: Long) : Delivery
}

sealed interface SubscribeResult {
    data class Subscribed(val subscription: Subscription) : SubscribeResult
    /** [from] is older than everything the retention ring still has. */
    data object CursorExpired : SubscribeResult
}

/**
 * A bounded per-subscriber queue. [offer] (called from [EventHub.emit], never blocking) either
 * enqueues or, if full, replaces the queue with a single [Delivery.Gap] and closes -- the consumer
 * must re-snapshot or resubscribe from a cursor, never wait for space to free up.
 */
class Subscription internal constructor(private val filter: EventFilter, private val capacity: Int) {
    private val queue = ArrayDeque<Delivery>()
    private var lastDeliveredSeq = 0L

    var isClosed = false
        private set

    internal fun offer(record: EventRecord) {
        if (isClosed || !filter.matches(record)) return
        if (queue.size >= capacity) {
            queue.clear()
            queue.addLast(Delivery.Gap(lastDeliveredSeq))
            isClosed = true
            return
        }
        queue.addLast(Delivery.Event(record))
    }

    /** Next queued item, or null if there is nothing waiting right now. */
    fun poll(): Delivery? {
        val item = queue.removeFirstOrNull() ?: return null
        if (item is Delivery.Event) lastDeliveredSeq = item.record.seq
        return item
    }

    fun close() {
        isClosed = true
        queue.clear()
    }
}

/**
 * Stamps every emitted event with a sequence number and timestamp, keeps a bounded retention ring
 * (lastSeq is the current revision), and fans events out to bounded [Subscription]s. Publishing
 * (this class's [emit]) only ever enqueues; it never runs subscriber code and never blocks.
 */
class EventHub(
    private val retention: Int = DEFAULT_RETENTION,
    private val now: () -> Long,
) : EventSink {
    private val ring = ArrayDeque<EventRecord>()
    private val subscriptions = mutableListOf<Subscription>()

    var lastSeq: Long = 0
        private set

    override fun emit(source: ObjectId, payload: EventPayload) {
        lastSeq++
        val record = EventRecord(lastSeq, now(), source, payload.name, payload)
        ring.addLast(record)
        while (ring.size > retention) ring.removeFirst()

        subscriptions.removeAll { it.isClosed }
        subscriptions.forEach { it.offer(record) }
    }

    /** Replays retained events with seq > [from] matching [filter], then delivers new ones as they occur. */
    fun subscribe(filter: EventFilter, from: Long = lastSeq, capacity: Int): SubscribeResult {
        val oldestRetained = ring.firstOrNull()?.seq
        if (oldestRetained != null && from < oldestRetained - 1) return SubscribeResult.CursorExpired

        val subscription = Subscription(filter, capacity)
        ring.asSequence().filter { it.seq > from }.forEach { subscription.offer(it) }
        subscriptions += subscription
        return SubscribeResult.Subscribed(subscription)
    }

    companion object {
        const val DEFAULT_RETENTION = 10_000
    }
}
