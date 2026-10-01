package com.a2z.nsdl.model

import com.a2z.nsdl.net.EthernetFrame
import com.a2z.nsdl.net.Ipv4Config
import com.a2z.nsdl.net.Ipv4Packet

/**
 * Typed event payloads. Semantics (source = the object the event is about):
 *
 * - [ObjectCreated]: an object was registered.
 * - [ObjectDeleted]: an object was removed, including one cascaded away by deleting its owner.
 * - [PowerOnStarted] (onPowerOn): power applied, boot began. The device cannot communicate yet.
 * - [BootCompleted]: boot finished; interfaces enabled, services started.
 * - [PoweredOff] (onPowerOff): power removed; services stopped, interfaces disabled, pending work invalidated.
 * - [Connected] / [Disconnected] (source = cable): physical attachment changed. Says nothing about link availability.
 * - [LinkStateChanged] (source = interface): operational link availability changed.
 * - [FrameSent] (onPacketSent, source = interface): a frame was handed to the attached medium.
 * - [FrameReceived] (source = interface): a frame arrived and passed the destination-MAC filter.
 * - [FrameDropped]: a frame was discarded; [DropReason] says where and why.
 * - [PacketAccepted] (onPacketReceived, source = interface): an IPv4/UDP packet was delivered to a bound listener.
 * - [ProtocolStateChanged] (source = protocol component): e.g. DHCP client SELECTING -> REQUESTING.
 * - [NetworkConfigChanged] (source = interface): IP configuration applied or cleared.
 * - [ConfigurationChanged]: persistent configuration updated by a command.
 */
sealed interface EventPayload {
    val name: String get() = this::class.simpleName!!

    data class ObjectCreated(val type: String, val kind: ObjectKind) : EventPayload
    data class ObjectDeleted(val type: String, val kind: ObjectKind) : EventPayload
    data class PowerOnStarted(val generation: Long) : EventPayload
    data class BootCompleted(val generation: Long) : EventPayload
    data class PoweredOff(val generation: Long, val previous: PowerState) : EventPayload
    data class Connected(val endpointA: ObjectId, val endpointB: ObjectId) : EventPayload
    data class Disconnected(val endpointA: ObjectId, val endpointB: ObjectId) : EventPayload
    data class LinkStateChanged(val up: Boolean) : EventPayload
    data class FrameSent(val frame: EthernetFrame) : EventPayload
    data class FrameReceived(val frame: EthernetFrame) : EventPayload
    data class FrameDropped(val frame: EthernetFrame, val reason: DropReason) : EventPayload
    data class PacketAccepted(val packet: Ipv4Packet) : EventPayload
    data class ProtocolStateChanged(val protocol: String, val from: String, val to: String, val detail: String = "") : EventPayload
    data class NetworkConfigChanged(val config: Ipv4Config?) : EventPayload
    data class ConfigurationChanged(val properties: Map<String, Any?>) : EventPayload
    data class DecisionRecorded(val record: DecisionRecord) : EventPayload

    companion object {
        val NAMES: Set<String> = setOf(
            "ObjectCreated", "ObjectDeleted", "PowerOnStarted", "BootCompleted", "PoweredOff", "Connected", "Disconnected",
            "LinkStateChanged", "FrameSent", "FrameReceived", "FrameDropped", "PacketAccepted",
            "ProtocolStateChanged", "NetworkConfigChanged", "ConfigurationChanged", "DecisionRecorded",
        )
    }
}

enum class DropReason {
    /** Sender's link was unavailable (unplugged, peer off, or own interface disabled). */
    LINK_DOWN,
    /** The cable was disconnected (or reconnected) while the frame was in flight. */
    DISCONNECTED_IN_FLIGHT,
    /** The receiving interface was disabled when the frame arrived. */
    RECEIVER_DISABLED,
    /** Destination MAC is neither ours nor broadcast. */
    NOT_FOR_US,
    /** No listener is bound for the destination protocol/port, or the IP destination is not ours. */
    NO_LISTENER,
}
