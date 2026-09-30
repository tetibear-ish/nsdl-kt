package com.a2z.nsdl.ipc

import com.a2z.nsdl.ip.toState
import com.a2z.nsdl.model.EventPayload

/** Converts a typed [EventPayload] into a JSON-safe Map for the wire, exhaustively over every kind. */
object WireMapper {
    fun toData(payload: EventPayload): Map<String, Any?> = when (payload) {
        is EventPayload.ObjectCreated -> mapOf("type" to payload.type, "kind" to payload.kind.name)
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
        is EventPayload.ConfigurationChanged -> mapOf("properties" to payload.properties)
    }
}
