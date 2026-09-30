package com.a2z.nsdl.ipc

import com.a2z.nsdl.app.Command
import com.a2z.nsdl.app.CommandError
import com.a2z.nsdl.app.EndpointRef
import com.a2z.nsdl.app.ErrorCode
import com.a2z.nsdl.events.EventFilter
import com.a2z.nsdl.ipc.json.Json
import com.a2z.nsdl.ipc.json.JsonParseException
import com.a2z.nsdl.model.ObjectId
import kotlin.time.Duration.Companion.milliseconds

/** Something a decoded request asks the server to do: run a [Command], or manage a subscription (not a Command). */
sealed interface IpcOperation {
    data class Run(val command: Command) : IpcOperation
    data class Subscribe(val filter: EventFilter, val from: Long, val capacity: Int) : IpcOperation
    data class Unsubscribe(val subscriptionId: String) : IpcOperation
}

sealed interface DecodeResult {
    data class Decoded(val id: String?, val operation: IpcOperation) : DecodeResult
    data class Failed(val id: String?, val error: CommandError) : DecodeResult
}

/** Decodes one NDJSON request line: {"v":1,"id":..,"op":..,"params":{..}}. */
object RequestCodec {
    private const val PROTOCOL_VERSION = 1L

    fun decode(line: String): DecodeResult {
        val root = try {
            Json.parse(line)
        } catch (e: JsonParseException) {
            return DecodeResult.Failed(null, CommandError(ErrorCode.INVALID_REQUEST, "malformed JSON: ${e.message}"))
        }
        if (root !is Map<*, *>) return DecodeResult.Failed(null, CommandError(ErrorCode.INVALID_REQUEST, "request must be a JSON object"))

        val id = root["id"] as? String

        val version = root["v"]
        if (version != PROTOCOL_VERSION) {
            return DecodeResult.Failed(id, CommandError(ErrorCode.UNSUPPORTED_VERSION, "unsupported version: $version"))
        }

        val op = root["op"] as? String ?: return DecodeResult.Failed(id, CommandError(ErrorCode.INVALID_REQUEST, "missing 'op'"))
        @Suppress("UNCHECKED_CAST")
        val params = (root["params"] as? Map<String, Any?>) ?: emptyMap()

        val operation = try {
            decodeOperation(op, params) ?: return DecodeResult.Failed(id, CommandError(ErrorCode.UNKNOWN_OP, "unknown op '$op'"))
        } catch (e: InvalidParams) {
            return DecodeResult.Failed(id, CommandError(ErrorCode.INVALID_REQUEST, e.message ?: "invalid params for '$op'"))
        }
        return DecodeResult.Decoded(id, operation)
    }

    private class InvalidParams(message: String) : Exception(message)

    private fun decodeOperation(op: String, params: Map<String, Any?>): IpcOperation? = when (op) {
        "listTypes" -> IpcOperation.Run(Command.ListTypes)
        "listObjects" -> IpcOperation.Run(Command.ListObjects)
        "inspect" -> IpcOperation.Run(Command.Inspect(stringParam(params, "id")))
        "create" -> IpcOperation.Run(Command.Create(stringParam(params, "id"), stringParam(params, "type"), propsParam(params, "props")))
        "applyTopology" -> IpcOperation.Run(Command.ApplyTopology(decodeTopologyBatch(params)))
        "connect" -> IpcOperation.Run(
            Command.Connect(stringParam(params, "cableId"), EndpointRef(stringParam(params, "a")), EndpointRef(stringParam(params, "b"))),
        )
        "disconnect" -> IpcOperation.Run(Command.Disconnect(stringParam(params, "cableId")))
        "configure" -> IpcOperation.Run(Command.Configure(stringParam(params, "id"), propsParam(params, "props")))
        "powerOn" -> IpcOperation.Run(Command.PowerOn(stringParam(params, "id")))
        "powerOff" -> IpcOperation.Run(Command.PowerOff(stringParam(params, "id")))
        "delete" -> IpcOperation.Run(Command.Delete(stringParam(params, "id")))
        "advance" -> IpcOperation.Run(Command.Advance(longParam(params, "durationMs").milliseconds))
        "subscribe" -> IpcOperation.Subscribe(
            filter = EventFilter(
                objectId = (params["objectId"] as? String)?.let { ObjectId(it) },
                types = (params["types"] as? List<*>)?.map { it as String }?.toSet(),
            ),
            from = (params["from"] as? Long) ?: 0L,
            capacity = intParam(params, "capacity"),
        )
        "unsubscribe" -> IpcOperation.Unsubscribe(stringParam(params, "subscriptionId"))
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeTopologyBatch(params: Map<String, Any?>): List<Command> {
        val objects = (params["objects"] as? List<Map<String, Any?>>) ?: emptyList()
        val connections = (params["connections"] as? List<Map<String, Any?>>) ?: emptyList()
        return objects.map { o -> Command.Create(stringParam(o, "id"), stringParam(o, "type"), propsParam(o, "props")) } +
            connections.map { c -> Command.Connect(stringParam(c, "cableId"), EndpointRef(stringParam(c, "a")), EndpointRef(stringParam(c, "b"))) }
    }

    private fun stringParam(params: Map<String, Any?>, key: String): String =
        params[key] as? String ?: throw InvalidParams("missing or non-string param '$key'")

    private fun longParam(params: Map<String, Any?>, key: String): Long =
        params[key] as? Long ?: throw InvalidParams("missing or non-integer param '$key'")

    private fun intParam(params: Map<String, Any?>, key: String): Int =
        (params[key] as? Long)?.toInt() ?: throw InvalidParams("missing or non-integer param '$key'")

    @Suppress("UNCHECKED_CAST")
    private fun propsParam(params: Map<String, Any?>, key: String): Map<String, Any?> = (params[key] as? Map<String, Any?>) ?: emptyMap()
}
