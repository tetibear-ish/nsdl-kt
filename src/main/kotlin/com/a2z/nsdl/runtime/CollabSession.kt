package com.a2z.nsdl.runtime

/**
 * A UI-only canvas position. Deliberately outside `com.a2z.nsdl.model`/`events`: it carries no simulation
 * meaning, is never produced by [com.a2z.nsdl.app.SimulationService] and must never be replayed as part of
 * deterministic simulation state (see [CollabSession] doc).
 */
data class Position(val x: Double, val y: Double)

/** One accepted change to the shared session: moved node positions and/or node ids removed (e.g. on delete). */
data class SessionPatch(val revision: Long, val changed: Map<String, Position> = emptyMap(), val removed: Set<String> = emptySet())

sealed interface MoveOutcome {
    data class Applied(val patch: SessionPatch) : MoveOutcome
    /** [baseRevision] was behind that node's current revision: a concurrent edit won. The caller should
     * reconcile its optimistic local state to [current] rather than retrying blindly. */
    data class Conflict(val current: Position?, val currentRevision: Long) : MoveOutcome
    /** The same (clientId, requestId) was already used for a different move: same shape as the runtime's
     * command-level IDEMPOTENCY_CONFLICT, scoped per client so two clients may reuse the same request id. */
    data object IdempotencyConflict : MoveOutcome
}

sealed interface SessionDelivery {
    data class Patch(val patch: SessionPatch) : SessionDelivery
    /** The subscriber fell behind past this subscription's capacity; it is now closed. */
    data class Gap(val lastDeliveredRevision: Long) : SessionDelivery
}

sealed interface SessionSubscribeResult {
    data class Subscribed(val subscription: SessionSubscription) : SessionSubscribeResult
    data object CursorExpired : SessionSubscribeResult
}

/** A bounded per-subscriber queue, mirroring [com.a2z.nsdl.events.Subscription]'s offer/poll/Gap contract. */
class SessionSubscription internal constructor(private val capacity: Int) {
    private val queue = ArrayDeque<SessionDelivery>()
    private var lastDeliveredRevision = 0L

    var isClosed = false
        private set

    internal fun offer(patch: SessionPatch) {
        if (isClosed) return
        if (queue.size >= capacity) {
            queue.clear()
            queue.addLast(SessionDelivery.Gap(lastDeliveredRevision))
            isClosed = true
            return
        }
        queue.addLast(SessionDelivery.Patch(patch))
    }

    fun poll(): SessionDelivery? {
        val item = queue.removeFirstOrNull() ?: return null
        if (item is SessionDelivery.Patch) lastDeliveredRevision = item.patch.revision
        return item
    }

    fun close() {
        isClosed = true
        queue.clear()
    }
}

/**
 * Server-owned shared editor-session state: node canvas positions, synchronized across every connected
 * client. This is deliberately *not* part of [com.a2z.nsdl.events.EventHub]/[com.a2z.nsdl.app.SimulationService]:
 * positions are UI metadata the simulation core has no notion of, and keeping them out of the deterministic
 * event stream means a lab's seeded replay trace never depends on where a human happened to drag a node.
 *
 * Despite that separation, the shape intentionally mirrors [com.a2z.nsdl.events.EventHub]: a monotonic
 * revision, a bounded retention ring, and bounded per-subscriber queues that overflow into a single Gap.
 * [com.a2z.nsdl.runtime.SimulationRuntime] thread-confines this exactly like [InputJournal]: all mutation
 * and subscription happens on the single runtime thread, so revisions here never race.
 *
 * Conflict handling: each node has its own last-write revision. A [move] citing a [baseRevision] older than
 * that node's current revision is rejected as [MoveOutcome.Conflict] instead of silently clobbering a newer
 * concurrent edit -- the caller reconciles to the returned authoritative position. Unrelated nodes moving
 * does not stale a pending edit to a different node (see the per-id revision test).
 */
class CollabSession(private val retention: Int = DEFAULT_RETENTION, idempotencyCapacity: Int = 1000) {
    private val positions = LinkedHashMap<String, Position>()
    private val nodeRevision = HashMap<String, Long>()
    private val ring = ArrayDeque<SessionPatch>()
    private val subscriptions = mutableListOf<SessionSubscription>()
    private val idempotency = object : LinkedHashMap<String, Pair<MoveRequestPayload, MoveOutcome>>(idempotencyCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<MoveRequestPayload, MoveOutcome>>) =
            size > idempotencyCapacity
    }

    var revision: Long = 0
        private set

    private data class MoveRequestPayload(val id: String, val position: Position, val baseRevision: Long?)

    fun snapshot(): SessionPatch = SessionPatch(revision, positions.toMap())

    fun move(clientId: String, requestId: String?, id: String, position: Position, baseRevision: Long?): MoveOutcome {
        val payload = MoveRequestPayload(id, position, baseRevision)
        val cacheKey = requestId?.let { "$clientId\u0000$it" }
        if (cacheKey != null) {
            val cached = idempotency[cacheKey]
            if (cached != null) {
                return if (cached.first == payload) cached.second else MoveOutcome.IdempotencyConflict
            }
        }

        val currentRevision = nodeRevision[id] ?: 0L
        if (baseRevision != null && baseRevision < currentRevision) {
            val conflict = MoveOutcome.Conflict(positions[id], currentRevision)
            if (cacheKey != null) idempotency[cacheKey] = payload to conflict
            return conflict
        }

        revision += 1
        positions[id] = position
        nodeRevision[id] = revision
        val patch = SessionPatch(revision, changed = mapOf(id to position))
        publish(patch)
        val outcome = MoveOutcome.Applied(patch)
        if (cacheKey != null) idempotency[cacheKey] = payload to outcome
        return outcome
    }

    /** Clears a node's position, e.g. after the object itself was deleted, and broadcasts the removal. */
    fun remove(id: String): SessionPatch {
        positions.remove(id)
        nodeRevision.remove(id)
        revision += 1
        val patch = SessionPatch(revision, removed = setOf(id))
        publish(patch)
        return patch
    }

    private fun publish(patch: SessionPatch) {
        ring.addLast(patch)
        while (ring.size > retention) ring.removeFirst()
        subscriptions.removeAll { it.isClosed }
        subscriptions.forEach { it.offer(patch) }
    }

    /** Replays retained patches after [from], then delivers new ones as they occur -- the reconnect path. */
    fun subscribe(from: Long = revision, capacity: Int): SessionSubscribeResult {
        val oldestRetained = ring.firstOrNull()?.revision
        if (oldestRetained != null && from < oldestRetained - 1) return SessionSubscribeResult.CursorExpired

        val subscription = SessionSubscription(capacity)
        ring.asSequence().filter { it.revision > from }.forEach { subscription.offer(it) }
        subscriptions += subscription
        return SessionSubscribeResult.Subscribed(subscription)
    }

    companion object {
        const val DEFAULT_RETENTION = 2000
    }
}
