package com.a2z.nsdl

import com.a2z.nsdl.app.TypeRegistry
import com.a2z.nsdl.app.types.Cat5CableType
import com.a2z.nsdl.app.types.DhcpServerHostType
import com.a2z.nsdl.app.types.EthernetSwitchType
import com.a2z.nsdl.app.types.PrinterType
import com.a2z.nsdl.events.EventHub
import com.a2z.nsdl.runtime.SimulationRuntime
import com.a2z.nsdl.sim.VirtualScheduler

/**
 * The only place that constructs the runtime's concrete pieces: the scheduler, the event hub, the
 * type registry (with every known object type), and the runtime itself. Nothing downstream of this
 * should call a concrete class's constructor directly.
 */
class Composition(randomSeed: Long = System.nanoTime()) {
    val scheduler = VirtualScheduler()
    val eventHub = EventHub(now = { scheduler.now.millis })
    val registry = TypeRegistry().apply {
        register(PrinterType)
        register(DhcpServerHostType)
        register(EthernetSwitchType)
        register(Cat5CableType)
    }
    val runtime = SimulationRuntime(scheduler, eventHub, registry, randomSeed = randomSeed)

    fun close() = runtime.close()
}
