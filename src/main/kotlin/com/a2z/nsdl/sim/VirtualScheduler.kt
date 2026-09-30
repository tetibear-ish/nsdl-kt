package com.a2z.nsdl.sim

import java.util.PriorityQueue
import kotlin.time.Duration

data class AdvanceResult(val now: SimTime, val eventsProcessed: Int, val truncated: Boolean)

/**
 * Deterministic discrete-event scheduler. Work runs in (time, scheduling order) order,
 * so simultaneous events are ordered FIFO. Not thread-safe: it must only be driven from
 * the runtime execution boundary.
 */
class VirtualScheduler : Scheduler {
    private class Entry(val at: SimTime, val order: Long, val action: () -> Unit) {
        var cancelled = false
    }

    private val queue = PriorityQueue<Entry>(compareBy<Entry>({ it.at }, { it.order }))
    private var nextOrder = 0L

    override var now: SimTime = SimTime(0)
        private set

    override fun schedule(delay: Duration, action: () -> Unit): Cancellable {
        require(!delay.isNegative()) { "delay must not be negative" }
        val entry = Entry(now + delay, nextOrder++, action)
        queue += entry
        return Cancellable { entry.cancelled = true }
    }

    val pendingCount: Int get() = queue.count { !it.cancelled }

    /**
     * Runs due work until [duration] of virtual time has elapsed or [maxEvents] callbacks
     * have run. When the budget runs out the clock stays at the last processed event.
     */
    fun advanceBy(duration: Duration, maxEvents: Int = DEFAULT_MAX_EVENTS): AdvanceResult {
        require(!duration.isNegative()) { "duration must not be negative" }
        val target = now + duration
        var processed = 0
        while (true) {
            val head = queue.peek() ?: break
            if (head.at > target) break
            if (head.cancelled) { queue.poll(); continue }
            if (processed >= maxEvents) return AdvanceResult(now, processed, truncated = true)
            queue.poll()
            now = head.at
            processed++
            head.action()
        }
        now = target
        return AdvanceResult(now, processed, truncated = false)
    }

    companion object {
        const val DEFAULT_MAX_EVENTS = 100_000
    }
}
