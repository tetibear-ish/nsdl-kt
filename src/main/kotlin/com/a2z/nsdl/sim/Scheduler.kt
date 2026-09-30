package com.a2z.nsdl.sim

import kotlin.time.Duration

/** A point in virtual simulation time, in milliseconds since the simulation started. */
data class SimTime(val millis: Long) : Comparable<SimTime> {
    operator fun plus(d: Duration): SimTime = SimTime(millis + d.inWholeMilliseconds)
    override fun compareTo(other: SimTime): Int = millis.compareTo(other.millis)
    override fun toString(): String = "t=${millis}ms"
}

fun interface Cancellable {
    fun cancel()
}

/**
 * Virtual-time scheduling as seen by simulation components. "Waiting" means scheduling
 * a callback and returning; nothing ever blocks a thread.
 */
interface Scheduler {
    val now: SimTime
    fun schedule(delay: Duration, action: () -> Unit): Cancellable
}
