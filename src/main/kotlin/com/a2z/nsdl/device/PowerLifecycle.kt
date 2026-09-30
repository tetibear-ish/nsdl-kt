package com.a2z.nsdl.device

import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.PowerState
import com.a2z.nsdl.sim.Scheduler
import com.a2z.nsdl.sim.WorkScope
import kotlin.time.Duration

enum class PowerChange { CHANGED, UNCHANGED }

/** Device-specific behavior composed behind the shared lifecycle. */
interface LifecycleHooks {
    /** Boot finished. [scope] is valid for this run only and is closed on power-off. */
    fun onBooted(scope: WorkScope)
    /** Power is being removed: stop services and disable interfaces. Called before [EventPayload.PoweredOff]. */
    fun onStopping()
}

/**
 * Shared power lifecycle: OFF -> BOOTING -> ON, and back to OFF from either powered state.
 * Each power-on starts a new generation with its own [WorkScope]; power-off closes the scope,
 * which invalidates the pending boot timer and any work scheduled by services in that run.
 * Repeated power-on (while BOOTING or ON) and power-off (while OFF) are no-ops.
 */
class PowerLifecycle(
    private val ownerId: ObjectId,
    private val scheduler: Scheduler,
    private val events: EventSink,
    private val bootDuration: () -> Duration,
    private val hooks: LifecycleHooks,
) {
    var state = PowerState.OFF
        private set
    var generation = 0L
        private set
    private var scope: WorkScope? = null

    fun powerOn(): PowerChange {
        if (state != PowerState.OFF) return PowerChange.UNCHANGED
        generation++
        val run = WorkScope(scheduler).also { scope = it }
        state = PowerState.BOOTING
        events.emit(ownerId, EventPayload.PowerOnStarted(generation))
        run.schedule(bootDuration()) {
            state = PowerState.ON
            events.emit(ownerId, EventPayload.BootCompleted(generation))
            hooks.onBooted(run)
        }
        return PowerChange.CHANGED
    }

    fun powerOff(): PowerChange {
        if (state == PowerState.OFF) return PowerChange.UNCHANGED
        val previous = state
        scope?.close()
        scope = null
        hooks.onStopping()
        state = PowerState.OFF
        events.emit(ownerId, EventPayload.PoweredOff(generation, previous))
        return PowerChange.CHANGED
    }
}
