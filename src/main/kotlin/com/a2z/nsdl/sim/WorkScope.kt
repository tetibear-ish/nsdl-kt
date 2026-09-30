package com.a2z.nsdl.sim

import kotlin.time.Duration

/**
 * Work that belongs to one run (one power-on generation) of a component. Closing the scope
 * cancels every pending timer and makes any late callback a no-op, so work from an earlier
 * run can never mutate a later one.
 */
class WorkScope(private val scheduler: Scheduler) : Scheduler {
    private val pending = mutableSetOf<Cancellable>()

    var isOpen = true
        private set

    override val now: SimTime get() = scheduler.now

    override fun schedule(delay: Duration, action: () -> Unit): Cancellable {
        if (!isOpen) return Cancellable {}
        lateinit var handle: Cancellable
        handle = scheduler.schedule(delay) {
            pending -= handle
            if (isOpen) action()
        }
        pending += handle
        return Cancellable { handle.cancel(); pending -= handle }
    }

    fun close() {
        isOpen = false
        pending.toList().forEach { it.cancel() }
        pending.clear()
    }
}
