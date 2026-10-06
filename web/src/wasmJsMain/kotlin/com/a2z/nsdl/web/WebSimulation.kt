package com.a2z.nsdl.web

import com.a2z.nsdl.app.Command
import com.a2z.nsdl.app.CommandResult
import com.a2z.nsdl.app.EndpointRef
import com.a2z.nsdl.app.ObjectTypeSchema
import com.a2z.nsdl.app.SimulationService
import com.a2z.nsdl.app.TypeRegistry
import com.a2z.nsdl.app.types.Cat5CableType
import com.a2z.nsdl.app.types.ComputerType
import com.a2z.nsdl.app.types.DhcpServerHostType
import com.a2z.nsdl.app.types.EthernetSwitchType
import com.a2z.nsdl.app.types.PrinterType
import com.a2z.nsdl.app.types.WebServerType
import com.a2z.nsdl.dhcp.PacketDecoder
import com.a2z.nsdl.events.EventHub
import com.a2z.nsdl.events.EventRecord
import com.a2z.nsdl.ip.PortForward
import com.a2z.nsdl.ipc.json.Json
import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.sim.VirtualScheduler
import kotlin.time.Duration.Companion.milliseconds

external fun startNsdl(execute: (String) -> String, graph: () -> String, command: (String) -> String)

fun main() {
    val simulation = WebSimulation()
    startNsdl(simulation::execute, simulation::graphJson, simulation::executeRequest)
}

/** Browser-local command boundary. It mirrors the terminal shell without JVM sockets or threads. */
class WebSimulation(seed: Long = 0L) {
    private var scheduler = VirtualScheduler()
    private var events = EventHub(now = { scheduler.now.millis })
    private var service = newService(seed)
    private var deliveredSeq = 0L

    fun execute(line: String): String = try {
        val words = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return result(false, "")
        if (words[0] == "help") return result(false, HELP)
        if (words[0] == "reset") {
            val seed = words.getOrNull(1)?.toLongOrNull() ?: 0L
            scheduler = VirtualScheduler()
            events = EventHub(now = { scheduler.now.millis })
            service = newService(seed)
            deliveredSeq = 0L
            return result(true, mapOf("seed" to seed))
        }
        encode(service.handle(parse(words)))
    } catch (e: Throwable) {
        Json.write(mapOf("ok" to false, "error" to (e.message ?: "invalid command")))
    }

    fun graphJson(): String {
        val snapshots = (service.handle(Command.ListObjects) as CommandResult.Ok).data
        return Json.write(toWire(snapshots))
    }

    /** Executes the versioned JSON command envelope used by the React transport. */
    fun executeRequest(request: String): String = try {
        val root = Json.parse(request) as? Map<*, *> ?: error("request must be a JSON object")
        require(root["v"] == 1L) { "unsupported version: ${root["v"]}" }
        val op = root["op"] as? String ?: error("missing 'op'")
        @Suppress("UNCHECKED_CAST")
        val params = root["params"] as? Map<String, Any?> ?: emptyMap()
        encode(service.handle(parseOperation(op, params)))
    } catch (e: Throwable) {
        Json.write(mapOf("ok" to false, "error" to mapOf("code" to "INVALID_REQUEST", "message" to (e.message ?: "invalid request"))))
    }

    private fun parseOperation(op: String, params: Map<String, Any?>): Command = when (op) {
        "listTypes" -> Command.ListTypes
        "listObjects" -> Command.ListObjects
        "inspect" -> Command.Inspect(stringParam(params, "id"))
        "invoke" -> Command.Invoke(stringParam(params, "id"), stringParam(params, "action"), propsParam(params["params"]))
        "analyzeGraph" -> Command.AnalyzeGraph(stringParam(params, "id"))
        "create" -> Command.Create(stringParam(params, "id"), stringParam(params, "type"), propsParam(params))
        "applyTopology" -> {
            @Suppress("UNCHECKED_CAST")
            val objects = params["objects"] as? List<Map<String, Any?>> ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val connections = params["connections"] as? List<Map<String, Any?>> ?: emptyList()
            Command.ApplyTopology(
                objects.map { Command.Create(stringParam(it, "id"), stringParam(it, "type"), propsParam(it)) } +
                    connections.map {
                        Command.Connect(stringParam(it, "cableId"), EndpointRef(stringParam(it, "a")), EndpointRef(stringParam(it, "b")))
                    },
            )
        }
        "connect" -> Command.Connect(stringParam(params, "cableId"), EndpointRef(stringParam(params, "a")), EndpointRef(stringParam(params, "b")))
        "disconnect" -> Command.Disconnect(stringParam(params, "cableId"))
        "configure" -> Command.Configure(stringParam(params, "id"), propsParam(params))
        "powerOn" -> Command.PowerOn(stringParam(params, "id"))
        "powerOff" -> Command.PowerOff(stringParam(params, "id"))
        "delete" -> Command.Delete(stringParam(params, "id"))
        "advance" -> Command.Advance(longParam(params, "durationMs").milliseconds)
        else -> error("unknown op '$op'")
    }

    private fun stringParam(params: Map<String, Any?>, name: String): String =
        params[name] as? String ?: error("missing or non-string param '$name'")

    private fun longParam(params: Map<String, Any?>, name: String): Long =
        params[name] as? Long ?: error("missing or non-integer param '$name'")

    @Suppress("UNCHECKED_CAST")
    private fun propsParam(params: Map<String, Any?>): Map<String, Any?> =
        params["props"] as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun propsParam(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()

    private fun newService(seed: Long): SimulationService {
        val registry = TypeRegistry().apply {
            register(PrinterType)
            register(ComputerType)
            register(DhcpServerHostType)
            register(EthernetSwitchType)
            register(Cat5CableType)
            register(WebServerType)
        }
        return SimulationService(scheduler, events, registry, randomSeed = seed)
    }

    private fun parse(words: List<String>): Command = when (words[0]) {
        "types" -> Command.ListTypes
        "list" -> Command.ListObjects
        "create" -> {
            require(words.size >= 3) { "usage: create TYPE ID [PROPERTY=VALUE ...]" }
            Command.Create(words[2], words[1], properties(words.drop(3)))
        }
        "connect" -> {
            require(words.size == 4) { "usage: connect CABLE ENDPOINT_A ENDPOINT_B" }
            Command.Connect(words[1], EndpointRef(words[2]), EndpointRef(words[3]))
        }
        "disconnect" -> {
            require(words.size == 2) { "usage: disconnect CABLE" }
            Command.Disconnect(words[1])
        }
        "inspect" -> {
            require(words.size == 2) { "usage: inspect ID" }
            Command.Inspect(words[1])
        }
        "analyze-graph" -> {
            require(words.size == 2) { "usage: analyze-graph ID" }
            Command.AnalyzeGraph(words[1])
        }
        "delete" -> {
            require(words.size == 2) { "usage: delete ID" }
            Command.Delete(words[1])
        }
        "power-on", "power-off" -> {
            require(words.size == 2) { "usage: ${words[0]} ID" }
            if (words[0] == "power-on") Command.PowerOn(words[1]) else Command.PowerOff(words[1])
        }
        "advance" -> {
            require(words.size == 2) { "usage: advance MILLISECONDS" }
            Command.Advance((words[1].toLongOrNull() ?: error("milliseconds must be an integer")).milliseconds)
        }
        else -> error("unknown command '${words[0]}'; type 'help'")
    }

    private fun properties(words: List<String>): Map<String, String> = words.associate { word ->
        val separator = word.indexOf('=')
        require(separator > 0) { "property '$word' must be NAME=VALUE" }
        word.substring(0, separator) to word.substring(separator + 1)
    }

    private fun encode(commandResult: CommandResult): String = when (commandResult) {
        is CommandResult.Ok -> result(commandResult.changed, toWire(commandResult.data))
        is CommandResult.Rejected -> Json.write(
            mapOf(
                "ok" to false,
                "error" to mapOf(
                    "code" to commandResult.error.code.name,
                    "message" to commandResult.error.message,
                    "details" to commandResult.error.details,
                ),
            ),
        )
    }

    private fun result(changed: Boolean, data: Any?): String {
        val pending = events.retainedAfter(deliveredSeq).orEmpty()
        deliveredSeq = events.lastSeq
        return Json.write(
            mapOf(
                "ok" to true,
                "changed" to changed,
                "revision" to events.lastSeq,
                "data" to data,
                "events" to pending.map(::eventToWire),
            ),
        )
    }

    private fun eventToWire(record: EventRecord): Map<String, Any?> = mapOf(
        "type" to "event",
        "seq" to record.seq,
        "timeMs" to record.timeMs,
        "source" to record.source.value,
        "eventType" to record.type,
        "data" to when (val payload = record.payload) {
            is EventPayload.FrameDropped -> mapOf("reason" to payload.reason.name)
            is EventPayload.LinkStateChanged -> mapOf("up" to payload.up)
            is EventPayload.ProtocolStateChanged -> mapOf("protocol" to payload.protocol, "from" to payload.from, "to" to payload.to)
            is EventPayload.DecisionRecorded -> mapOf("record" to payload.record.toState())
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
            else -> emptyMap<String, Any?>()
        },
    )

    private fun toWire(value: Any?): Any? = when (value) {
        is ObjectSnapshot -> mapOf(
            "id" to value.id.value,
            "type" to value.type,
            "kind" to value.kind.name,
            "state" to value.state,
            "relations" to value.relations.mapValues { (_, ids) -> ids.map { it.value } },
        )
        is ObjectTypeSchema -> mapOf(
            "name" to value.name,
            "kind" to value.kind.name,
            "properties" to value.properties.map {
                mapOf(
                    "name" to it.name,
                    "type" to it.type.name,
                    "required" to it.required,
                    "default" to toWire(it.default),
                    "mutable" to it.mutable,
                    "description" to it.description,
                )
            },
            "interfaces" to value.interfaces.map { mapOf("name" to it.name, "media" to it.media.name) },
        )
        is List<*> -> value.map(::toWire)
        is Ipv4Address, is MacAddress, is PortForward -> value.toString()
        is LinkProfile -> value.name
        else -> value
    }

    companion object {
        private val HELP = """
            types | list | inspect ID | analyze-graph ID
            create TYPE ID [PROPERTY=VALUE ...]
            connect CABLE ENDPOINT_A ENDPOINT_B | disconnect CABLE
            power-on ID | power-off ID | delete ID | advance MILLISECONDS
            reset [SEED]
        """.trimIndent()
    }
}
