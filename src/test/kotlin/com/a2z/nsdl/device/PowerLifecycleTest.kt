package com.a2z.nsdl.device

import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.PowerState
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.sim.WorkScope
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PowerLifecycleTest {
    private val id = ObjectId("dev")
    private val scheduler = VirtualScheduler()
    private val sink = RecordingSink()
    private val hooks = object : LifecycleHooks {
        var booted = 0
        var stopped = 0
        var lastScope: WorkScope? = null
        override fun onBooted(scope: WorkScope) { booted++; lastScope = scope }
        override fun onStopping() { stopped++ }
    }
    private val lifecycle = PowerLifecycle(id, scheduler, sink, bootDuration = { 2.seconds }, hooks)

    @Test
    fun `power on boots in virtual time before the device becomes ready`() {
        lifecycle.powerOn()
        assertEquals(PowerState.BOOTING, lifecycle.state)
        assertEquals(listOf("PowerOnStarted"), sink.names())

        scheduler.advanceBy(1999.milliseconds)
        assertEquals(PowerState.BOOTING, lifecycle.state)
        assertEquals(0, hooks.booted)

        scheduler.advanceBy(1.milliseconds)
        assertEquals(PowerState.ON, lifecycle.state)
        assertEquals(1, hooks.booted)
        assertEquals(listOf("PowerOnStarted", "BootCompleted"), sink.names())
    }

    @Test
    fun `powering off during boot prevents the boot from ever completing`() {
        lifecycle.powerOn()
        scheduler.advanceBy(1.seconds)
        lifecycle.powerOff()

        scheduler.advanceBy(10.seconds)

        assertEquals(PowerState.OFF, lifecycle.state)
        assertEquals(0, hooks.booted)
        assertEquals(listOf("PowerOnStarted", "PoweredOff"), sink.names())
    }

    @Test
    fun `repeated power commands are idempotent`() {
        assertEquals(PowerChange.UNCHANGED, lifecycle.powerOff())
        assertEquals(PowerChange.CHANGED, lifecycle.powerOn())
        scheduler.advanceBy(1.seconds)
        assertEquals(PowerChange.UNCHANGED, lifecycle.powerOn(), "power-on while booting does not restart boot")
        scheduler.advanceBy(1.seconds)
        assertEquals(PowerState.ON, lifecycle.state, "boot still completes on the original schedule")
        assertEquals(PowerChange.UNCHANGED, lifecycle.powerOn())
        assertEquals(PowerChange.CHANGED, lifecycle.powerOff())
        assertEquals(PowerChange.UNCHANGED, lifecycle.powerOff())

        assertEquals(listOf("PowerOnStarted", "BootCompleted", "PoweredOff"), sink.names())
        assertEquals(1, hooks.booted)
    }

    @Test
    fun `work scheduled during one run cannot fire in a later run`() {
        lifecycle.powerOn()
        scheduler.advanceBy(2.seconds)
        var staleFired = false
        hooks.lastScope!!.schedule(5.seconds) { staleFired = true }

        lifecycle.powerOff()
        lifecycle.powerOn()
        scheduler.advanceBy(10.seconds)

        assertFalse(staleFired)
        assertEquals(PowerState.ON, lifecycle.state)
        assertEquals(2L, lifecycle.generation)
        assertTrue(hooks.lastScope!!.isOpen, "new run gets a fresh open scope")
    }

    @Test
    fun `stopping hook runs on power off so components can shut down`() {
        lifecycle.powerOn()
        scheduler.advanceBy(2.seconds)
        lifecycle.powerOff()
        assertEquals(1, hooks.stopped)
        assertTrue(sink.names().last() == "PoweredOff")
    }
}
