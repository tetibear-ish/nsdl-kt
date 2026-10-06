package com.a2z.nsdl.ipc

import com.a2z.nsdl.dhcp.PacketDecoder
import com.a2z.nsdl.ip.PortForward
import com.a2z.nsdl.ip.toState
import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.MacAddress

/** Converts a typed [EventPayload] into a JSON-safe Map for the wire, exhaustively over every kind. */
object WireMapper {
    fun toData(payload: EventPayload): Map<String, Any?> = when (payload) {
        is EventPayload.ObjectCreated -> mapOf("type" to payload.type, "kind" to payload.kind.name)
        is EventPayload.ObjectDeleted -> mapOf("type" to payload.type, "kind" to payload.kind.name)
        is EventPayload.PowerOnStarted -> mapOf("generation" to payload.generation)
        is EventPayload.BootCompleted -> mapOf("generation" to payload.generation)
        is EventPayload.PoweredOff -> mapOf("generation" to payload.generation, "previous" to payload.previous.name)
        is EventPayload.Connected -> mapOf("endpointA" to payload.endpointA.value, "endpointB" to payload.endpointB.value)
        is EventPayload.Disconnected -> mapOf("endpointA" to payload.endpointA.value, "endpointB" to payload.endpointB.value)
        is EventPayload.LinkStateChanged -> mapOf("up" to payload.up)
        is EventPayload.FrameSent -> mapOf("frame" to payload.frame.describe())
        is EventPayload.FrameReceived -> mapOf("frame" to payload.frame.describe())
        is EventPayload.FrameDropped -> mapOf("frame" to payload.frame.describe(), "reason" to payload.reason.name)
        is EventPayload.PacketAccepted -> mapOf("packet" to payload.packet.describe())
        is EventPayload.ProtocolStateChanged -> mapOf("protocol" to payload.protocol, "from" to payload.from, "to" to payload.to, "detail" to payload.detail)
        is EventPayload.NetworkConfigChanged -> mapOf("config" to payload.config?.toState())
        is EventPayload.ConfigurationChanged -> mapOf("properties" to payload.properties.mapValues { (_, value) -> plain(value) })
        is EventPayload.DecisionRecorded -> mapOf("record" to payload.record.toState())
        is EventPayload.ActionPerformed -> mapOf("action" to payload.action, "accepted" to payload.accepted, "detail" to payload.detail)
        is EventPayload.ApplicationEvent -> mapOf("application" to payload.application, "activity" to payload.activity, "detail" to payload.detail)
        is EventPayload.PacketObserved -> mapOf(
            "transitId" to payload.transitId,
            "sentAtMs" to payload.sentAtMs,
            "from" to payload.from.value,
            "to" to payload.to.value,
            "sourceMac" to payload.frame.src.toString(),
            "destMac" to payload.frame.dst.toString(),
            "frame" to payload.frame.describe(),
            "outcome" to payload.outcome.name,
            "dropReason" to payload.dropReason?.name,
        ) + PacketDecoder.decode(payload.frame)
    }

    /** Property values are typed (addresses, link profiles, port forwards); the wire carries their text form. */
    private fun plain(value: Any?): Any? = when (value) {
        is Ipv4Address, is MacAddress, is PortForward -> value.toString()
        is LinkProfile -> value.name
        is List<*> -> value.map(::plain)
        else -> value
    }
}
