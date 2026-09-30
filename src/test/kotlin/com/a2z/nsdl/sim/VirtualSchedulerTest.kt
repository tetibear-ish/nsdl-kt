package com.a2z.nsdl.sim

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class VirtualSchedulerTest {
    private val scheduler = VirtualScheduler()

    @Test
    fun `runs work in virtual time order and breaks ties by scheduling order`() {
        val log = mutableListOf<String>()
        scheduler.schedule(20.milliseconds) { log += "late" }
        scheduler.schedule(10.milliseconds) { log += "a" }
        scheduler.schedule(10.milliseconds) { log += "b" }

        scheduler.advanceBy(1.seconds)

        assertEquals(listOf("a", "b", "late"), log)
        assertEquals(SimTime(1000), scheduler.now)
    }

    @Test
    fun `scheduling returns immediately and nothing runs until time advances`() {
        var ran = false
        scheduler.schedule(5.seconds) { ran = true }

        scheduler.advanceBy(4.seconds)
        assertFalse(ran)

        scheduler.advanceBy(1.seconds)
        assertTrue(ran)
    }

    @Test
    fun `cancelled work never runs`() {
        var ran = false
        val handle = scheduler.schedule(10.milliseconds) { ran = true }
        handle.cancel()

        scheduler.advanceBy(1.seconds)

        assertFalse(ran)
    }

    @Test
    fun `work scheduled during advancement runs if it falls inside the window`() {
        val times = mutableListOf<Long>()
        scheduler.schedule(10.milliseconds) {
            times += scheduler.now.millis
            scheduler.schedule(10.milliseconds) { times += scheduler.now.millis }
        }

        scheduler.advanceBy(15.milliseconds)
        assertEquals(listOf(10L), times)

        scheduler.advanceBy(15.milliseconds)
        assertEquals(listOf(10L, 20L), times)
    }

    @Test
    fun `advancement is bounded by an event budget so self-rescheduling loops cannot hang`() {
        fun loop() { scheduler.schedule(0.milliseconds) { loop() } }
        loop()

        val result = scheduler.advanceBy(1.seconds, maxEvents = 100)

        assertTrue(result.truncated)
        assertEquals(100, result.eventsProcessed)
        assertEquals(SimTime(0), scheduler.now, "clock stops where the budget ran out")
    }
}
