package com.a2z.nsdl.ipc

import com.a2z.nsdl.app.CommandError
import com.a2z.nsdl.app.ObjectTypeSchema
import com.a2z.nsdl.events.EventRecord
import com.a2z.nsdl.ip.PortForward
import com.a2z.nsdl.ipc.json.Json
import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.MacAddress

/** Builds the NDJSON lines the server pushes: result/error replies and event/gap pushes. */
object ReplyCodec {
    fun encodeResult(id: String?, revision: Long, changed: Boolean, data: Any?): String = Json.write(
        mapOf("type" to "result", "id" to id, "revision" to revision, "changed" to changed, "data" to toWireData(data)),
    )

    fun encodeError(id: String?, error: CommandError): String = Json.write(
        mapOf(
            "type" to "error", "id" to id,
            "error" to mapOf("code" to error.code.name, "message" to error.message, "details" to error.details),
        ),
    )

    fun encodeEvent(subscriptionId: String, record: EventRecord): String = Json.write(
        mapOf(
            "type" to "event", "subscriptionId" to subscriptionId,
            "seq" to record.seq, "timeMs" to record.timeMs, "source" to record.source.value,
            "eventType" to record.type, "correlationId" to record.correlationId,
            "data" to WireMapper.toData(record.payload),
        ),
    )

    fun encodeGap(subscriptionId: String): String = Json.write(
        mapOf("type" to "gap", "subscriptionId" to subscriptionId, "resync" to true),
    )

    /** Snapshot state/relations are already JSON-safe by ObjectSnapshot's own contract; only the shape wraps. */
    private fun toWireData(value: Any?): Any? = when (value) {
        is ObjectSnapshot -> mapOf(
            "id" to value.id.value, "type" to value.type, "kind" to value.kind.name,
            "state" to value.state,
            "relations" to value.relations.mapValues { (_, ids) -> ids.map { it.value } },
        )
        is ObjectTypeSchema -> mapOf(
            "name" to value.name,
            "kind" to value.kind.name,
            "properties" to value.properties.map { property ->
                mapOf(
                    "name" to property.name,
                    "type" to property.type.name,
                    "required" to property.required,
                    "default" to toWireData(property.default),
                    "mutable" to property.mutable,
                    "description" to property.description,
                )
            },
            "interfaces" to value.interfaces.map { mapOf("name" to it.name, "media" to it.media.name) },
        )
        is List<*> -> value.map { toWireData(it) }
        is Ipv4Address, is MacAddress, is PortForward -> value.toString()
        is LinkProfile -> value.name
        else -> value
    }
}
