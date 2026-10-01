package com.a2z.nsdl.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CollabSessionTest {
    @Test
    fun `a fresh session has revision zero and no positions`() {
        val session = CollabSession()

        val snapshot = session.snapshot()

        assertEquals(0L, snapshot.revision)
        assertTrue(snapshot.changed.isEmpty())
    }

    @Test
    fun `moving a node applies it, bumps the revision, and the snapshot reflects it`() {
        val session = CollabSession()

        val outcome = session.move("client-a", "req-1", "printer1", Position(10.0, 20.0), baseRevision = null)

        assertTrue(outcome is MoveOutcome.Applied)
        val applied = outcome as MoveOutcome.Applied
        assertEquals(1L, applied.patch.revision)
        assertEquals(mapOf("printer1" to Position(10.0, 20.0)), applied.patch.changed)

        val snapshot = session.snapshot()
        assertEquals(1L, snapshot.revision)
        assertEquals(mapOf("printer1" to Position(10.0, 20.0)), snapshot.changed)
    }

    @Test
    fun `moving a node that already exists overwrites its position and bumps the revision again`() {
        val session = CollabSession()
        session.move("client-a", "req-1", "printer1", Position(10.0, 20.0), baseRevision = null)

        val outcome = session.move("client-a", "req-2", "printer1", Position(30.0, 40.0), baseRevision = null)

        assertTrue(outcome is MoveOutcome.Applied)
        assertEquals(2L, (outcome as MoveOutcome.Applied).patch.revision)
        assertEquals(mapOf("printer1" to Position(30.0, 40.0)), session.snapshot().changed)
    }

    @Test
    fun `repeating the same client's request id with the same payload replays the cached outcome without a new revision`() {
        val session = CollabSession()
        val request = { session.move("client-a", "req-1", "printer1", Position(10.0, 20.0), baseRevision = null) }

        val first = request()
        val second = request()

        assertEquals(first, second)
        assertEquals(1L, session.snapshot().revision, "the retry did not bump the revision again")
    }

    @Test
    fun `reusing the same client's request id with a different payload is an idempotency conflict`() {
        val session = CollabSession()
        session.move("client-a", "req-1", "printer1", Position(10.0, 20.0), baseRevision = null)

        val conflict = session.move("client-a", "req-1", "printer1", Position(99.0, 99.0), baseRevision = null)

        assertTrue(conflict is MoveOutcome.IdempotencyConflict)
        assertEquals(mapOf("printer1" to Position(10.0, 20.0)), session.snapshot().changed, "unchanged by the conflicting retry")
    }

    @Test
    fun `two different clients may each use the same request id without colliding -- it is client-scoped`() {
        val session = CollabSession()

        val a = session.move("client-a", "req-1", "printer1", Position(1.0, 1.0), baseRevision = null)
        val b = session.move("client-b", "req-1", "printer2", Position(2.0, 2.0), baseRevision = null)

        assertTrue(a is MoveOutcome.Applied)
        assertTrue(b is MoveOutcome.Applied)
        assertEquals(
            mapOf("printer1" to Position(1.0, 1.0), "printer2" to Position(2.0, 2.0)),
            session.snapshot().changed,
        )
    }

    @Test
    fun `a move based on a stale revision is rejected as a conflict and leaves state untouched`() {
        val session = CollabSession()
        session.move("client-a", "req-1", "printer1", Position(10.0, 20.0), baseRevision = null) // revision 1

        // client-b never observed revision 1, and tries to move based on revision 0.
        val outcome = session.move("client-b", "req-2", "printer1", Position(99.0, 99.0), baseRevision = 0L)

        assertTrue(outcome is MoveOutcome.Conflict)
        val conflict = outcome as MoveOutcome.Conflict
        assertEquals(Position(10.0, 20.0), conflict.current)
        assertEquals(1L, conflict.currentRevision)
        assertEquals(mapOf("printer1" to Position(10.0, 20.0)), session.snapshot().changed, "the stale move never applied")
    }

    @Test
    fun `a move citing the current revision for that node is accepted even if other nodes moved since`() {
        val session = CollabSession()
        session.move("client-a", "req-1", "printer1", Position(1.0, 1.0), baseRevision = null) // rev 1, printer1
        session.move("client-a", "req-2", "printer2", Position(2.0, 2.0), baseRevision = null) // rev 2, printer2 -- unrelated to printer1

        // client-b only ever saw printer1 at revision 1, which is still current for printer1 specifically.
        val outcome = session.move("client-b", "req-3", "printer1", Position(5.0, 5.0), baseRevision = 1L)

        assertTrue(outcome is MoveOutcome.Applied, "per-id revisions mean an unrelated node's edit does not stale this one")
    }

    @Test
    fun `removing a node clears its position and is broadcast as a removal`() {
        val session = CollabSession()
        session.move("client-a", "req-1", "printer1", Position(1.0, 1.0), baseRevision = null)

        val patch = session.remove("printer1")

        assertEquals(setOf("printer1"), patch.removed)
        assertTrue(session.snapshot().changed.isEmpty())
    }

    @Test
    fun `subscribing replays retained patches after the given revision`() {
        val session = CollabSession()
        session.move("client-a", "req-1", "printer1", Position(1.0, 1.0), baseRevision = null) // rev 1
        session.move("client-a", "req-2", "printer2", Position(2.0, 2.0), baseRevision = null) // rev 2

        val sub = (session.subscribe(from = 1L, capacity = 10) as SessionSubscribeResult.Subscribed).subscription
        val deliveries = generateSequence { sub.poll() }.toList()

        assertEquals(1, deliveries.size)
        assertEquals(mapOf("printer2" to Position(2.0, 2.0)), (deliveries[0] as SessionDelivery.Patch).patch.changed)
    }

    @Test
    fun `a newly accepted move is pushed to an existing subscriber`() {
        val session = CollabSession()
        val sub = (session.subscribe(capacity = 10) as SessionSubscribeResult.Subscribed).subscription

        session.move("client-a", "req-1", "printer1", Position(1.0, 1.0), baseRevision = null)

        val delivery = sub.poll()
        assertTrue(delivery is SessionDelivery.Patch)
        assertEquals(mapOf("printer1" to Position(1.0, 1.0)), (delivery as SessionDelivery.Patch).patch.changed)
    }

    @Test
    fun `subscribing with a revision older than the retention window is rejected as CursorExpired`() {
        val session = CollabSession(retention = 2)
        session.move("client-a", "req-1", "a", Position(0.0, 0.0), baseRevision = null) // rev 1, evicted
        session.move("client-a", "req-2", "b", Position(0.0, 0.0), baseRevision = null) // rev 2
        session.move("client-a", "req-3", "c", Position(0.0, 0.0), baseRevision = null) // rev 3 -- ring now holds [2, 3]

        assertEquals(SessionSubscribeResult.CursorExpired, session.subscribe(from = 0L, capacity = 10))
        assertTrue(session.subscribe(from = 1L, capacity = 10) is SessionSubscribeResult.Subscribed)
    }

    @Test
    fun `a full subscriber queue overflows into a single Gap and closes, without affecting other subscribers`() {
        val session = CollabSession()
        val small = (session.subscribe(capacity = 2) as SessionSubscribeResult.Subscribed).subscription
        val roomy = (session.subscribe(capacity = 10) as SessionSubscribeResult.Subscribed).subscription

        repeat(5) { session.move("client-a", "req-$it", "n$it", Position(it.toDouble(), 0.0), baseRevision = null) }

        assertTrue(small.isClosed)
        val smallDeliveries = generateSequence { small.poll() }.toList()
        assertEquals(listOf(SessionDelivery.Gap(0L)), smallDeliveries)
        assertFalse(roomy.isClosed)
        assertEquals(5, generateSequence { roomy.poll() }.toList().size)
    }

    @Test
    fun `an explicitly closed subscription receives nothing further`() {
        val session = CollabSession()
        val sub = (session.subscribe(capacity = 10) as SessionSubscribeResult.Subscribed).subscription

        sub.close()
        session.move("client-a", "req-1", "printer1", Position(1.0, 1.0), baseRevision = null)

        assertNull(sub.poll())
    }
}
