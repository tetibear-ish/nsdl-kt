package com.a2z.nsdl.device

import com.a2z.nsdl.link.EthernetInterface
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.Inspectable
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.model.PowerState
import com.a2z.nsdl.sim.Scheduler
import com.a2z.nsdl.sim.WorkScope
import kotlin.time.Duration

/** Power control surface shared by every device. */
interface PowerControl {
    val powerState: PowerState
    fun powerOn(): PowerChange
    fun powerOff(): PowerChange
}

/**
 * A host composed from reusable interfaces and services behind a shared [PowerLifecycle].
 * On boot, interfaces are enabled before services start, so a service never sees a link
 * transition it can't observe. On stop, services are stopped before interfaces are disabled,
 * so a service never sends into an interface that already went dark.
 */
class Device(
    override val id: ObjectId,
    val type: String,
    val interfaces: List<EthernetInterface>,
    val services: List<DeviceService>,
    private val bootDuration: () -> Duration,
    scheduler: Scheduler,
    events: EventSink,
) : Inspectable, PowerControl {

    private val lifecycle = PowerLifecycle(
        id, scheduler, events, bootDuration,
        object : LifecycleHooks {
            override fun onBooted(scope: WorkScope) {
                interfaces.forEach { it.enable() }
                services.forEach { it.start(scope) }
            }

            override fun onStopping() {
                services.forEach { it.stop() }
                interfaces.forEach { it.disable() }
            }
        },
    )

    override val powerState: PowerState get() = lifecycle.state
    override fun powerOn(): PowerChange = lifecycle.powerOn()
    override fun powerOff(): PowerChange = lifecycle.powerOff()

    override fun snapshot() = ObjectSnapshot(
        id, type, ObjectKind.DEVICE,
        // Single-interface devices (printer, computer, ...) declare a "mac" property so it can
        // be set at creation; surface the interface's own assigned value here too, under that
        // same name, so inspect() reflects what create()/configure() actually accept. Multi-
        // interface devices don't declare that property, so there's no single key to roll up to.
        state = mapOf(
            "power" to lifecycle.state.name,
            "bootMs" to bootDuration().inWholeMilliseconds,
            "generation" to lifecycle.generation,
        ) + interfaces.singleOrNull()?.let { mapOf("mac" to it.mac.toString()) }.orEmpty(),
        relations = mapOf(
            "interfaces" to interfaces.map { it.id },
            "services" to services.map { it.id },
        ),
    )
}
