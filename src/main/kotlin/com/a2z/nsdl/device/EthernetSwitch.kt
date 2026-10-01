package com.a2z.nsdl.device

import com.a2z.nsdl.link.EthernetInterface
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.DecisionAction
import com.a2z.nsdl.model.DecisionParents
import com.a2z.nsdl.model.DecisionRecord
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.Inspectable
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.model.PowerState
import com.a2z.nsdl.model.Responsibility
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
    private val events: EventSink,
    bootDuration: Duration = ZERO,
    private val decisionCapacity: Int = 100,
) : Inspectable, PowerControl {
    init {
        require(portCount >= 2) { "an Ethernet switch needs at least two ports" }
        require(decisionCapacity > 0) { "decision capacity must be positive" }
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
    private val decisions = mutableListOf<DecisionRecord>()
    private var nextDecision = 1L
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
        record(
            DecisionAction.LEARN_SOURCE,
            "source address observed on ingress port",
            frame,
            mapOf("ingress" to ingress.id.value, "sourceMac" to frame.src.toString()),
        )
        val learnedDestination = if (frame.dst.isBroadcast) null else learnedPorts[frame.dst]
        if (learnedDestination != null) {
            if (learnedDestination !== ingress) {
                record(
                    DecisionAction.FORWARD,
                    "destination learned on egress port",
                    frame,
                    mapOf("ingress" to ingress.id.value, "egress" to learnedDestination.id.value),
                )
                learnedDestination.send(frame)
            } else {
                record(
                    DecisionAction.DROP,
                    "destination learned on ingress port",
                    frame,
                    mapOf("ingress" to ingress.id.value),
                )
            }
        } else {
            val egress = ports.filter { it !== ingress }
            record(
                DecisionAction.FLOOD,
                if (frame.dst.isBroadcast) "broadcast destination" else "destination address not learned",
                frame,
                mapOf(
                    "ingress" to ingress.id.value,
                    "egress" to egress.joinToString(",") { it.id.value },
                ),
            )
            egress.forEach { it.send(frame) }
        }
    }

    private fun record(
        action: DecisionAction,
        reason: String,
        frame: EthernetFrame,
        attributes: Map<String, String>,
    ) {
        val record = DecisionRecord(
            id = "${id.value}:decision:${nextDecision++}",
            responsibility = Responsibility.SWITCHING,
            decision = action,
            reason = reason,
            parents = DecisionParents(),
            attributes = attributes + mapOf(
                "sourceMac" to frame.src.toString(),
                "destinationMac" to frame.dst.toString(),
                "etherType" to "0x${frame.etherType.toString(16).padStart(4, '0')}",
            ),
        )
        decisions += record
        if (decisions.size > decisionCapacity) decisions.removeAt(0)
        events.emit(id, EventPayload.DecisionRecorded(record))
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
            "decisions" to decisions.map { it.toState() },
        ),
        relations = mapOf("interfaces" to ports.map { it.id }),
    )
}
