package com.a2z.nsdl.device

import com.a2z.nsdl.link.EthernetInterface
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.Inspectable
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.model.PowerState
import com.a2z.nsdl.net.EthernetFrame
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.sim.Scheduler
import com.a2z.nsdl.sim.WorkScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * A small learning Ethernet switch. It learns source MAC addresses and forwards known unicasts to
 * one port; broadcasts and unknown unicasts are flooded to every other port. Its ports receive
 * promiscuously because a switch must inspect frames addressed to attached hosts, not to itself.
 */
class EthernetSwitch(
    override val id: ObjectId,
    portCount: Int,
    scheduler: Scheduler,
    events: EventSink,
    bootDuration: Duration = ZERO,
) : Inspectable, PowerControl {
    init {
        require(portCount >= 2) { "an Ethernet switch needs at least two ports" }
    }

    val ports: List<EthernetInterface> = List(portCount) { index ->
        EthernetInterface(
            id = id.child("port${index + 1}"),
            mac = MacAddress.local(0x1000 + index),
            events = events,
            ownerId = id,
            promiscuous = true,
        )
    }
    private val learnedPorts = mutableMapOf<MacAddress, EthernetInterface>()
    private val lifecycle = PowerLifecycle(
        ownerId = id,
        scheduler = scheduler,
        events = events,
        bootDuration = { bootDuration },
        hooks = object : LifecycleHooks {
            override fun onBooted(scope: WorkScope) {
                ports.forEach { it.enable() }
            }

            override fun onStopping() {
                learnedPorts.clear()
                ports.forEach { it.disable() }
            }
        },
    )

    init {
        ports.forEach { ingress -> ingress.bindUpperLayer { frame -> forward(ingress, frame) } }
    }

    private fun forward(ingress: EthernetInterface, frame: EthernetFrame) {
        learnedPorts[frame.src] = ingress
        val learnedDestination = if (frame.dst.isBroadcast) null else learnedPorts[frame.dst]
        if (learnedDestination != null) {
            if (learnedDestination !== ingress) learnedDestination.send(frame)
        } else {
            ports.asSequence().filter { it !== ingress }.forEach { it.send(frame) }
        }
    }

    override val powerState: PowerState get() = lifecycle.state
    override fun powerOn(): PowerChange = lifecycle.powerOn()
    override fun powerOff(): PowerChange = lifecycle.powerOff()

    override fun snapshot() = ObjectSnapshot(
        id = id,
        type = "ethernet-switch",
        kind = ObjectKind.DEVICE,
        state = mapOf(
            "power" to powerState.name,
            "ports" to ports.size,
            "learnedAddresses" to learnedPorts.keys.map { it.toString() }.sorted(),
        ),
        relations = mapOf("interfaces" to ports.map { it.id }),
    )
}
