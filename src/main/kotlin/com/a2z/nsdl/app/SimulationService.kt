package com.a2z.nsdl.app

import com.a2z.nsdl.device.PowerChange
import com.a2z.nsdl.link.LinkEndpoint
import com.a2z.nsdl.link.MediaType
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.sim.VirtualScheduler
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE

/** Caps applied to every [Command.Advance]. */
data class Limits(
    val maxEventsPerAdvance: Int = VirtualScheduler.DEFAULT_MAX_EVENTS,
    val maxAdvanceDuration: Duration = INFINITE,
)

/**
 * Validates and applies [Command]s against an in-memory registry of created objects.
 * Every handler validates fully before touching any state, per object: nothing here reads back
 * from a half-applied mutation to decide whether to reject.
 */
class SimulationService(
    private val scheduler: VirtualScheduler,
    private val events: EventSink,
    private val registry: TypeRegistry,
    private val limits: Limits = Limits(),
    randomSeed: Long = 0L,
) {
    /** Canonical, fully-coerced creation properties are retained so Configure can rebuild safely. */
    private data class Registered(
        val type: ObjectType,
        var obj: SimObject,
        var properties: Map<String, Any?>,
    )

    private var nextMacIndex = 1
    private val ctx = CreationContext(
        scheduler = scheduler,
        events = events,
        random = { id -> Random(randomSeed xor id.hashCode().toLong()) },
        nextMac = { MacAddress.local(nextMacIndex++) },
    )
    private val objects = mutableMapOf<ObjectId, Registered>()

    fun handle(command: Command): CommandResult = when (command) {
        is Command.Create -> handleCreate(command)
        is Command.ApplyTopology -> handleApplyTopology(command)
        is Command.Connect -> handleConnect(command)
        is Command.Disconnect -> handleDisconnect(command)
        is Command.Configure -> handleConfigure(command)
        is Command.PowerOn -> handlePower(command.id, on = true)
        is Command.PowerOff -> handlePower(command.id, on = false)
        is Command.Advance -> handleAdvance(command)
        Command.ListTypes -> CommandResult.Ok(registry.list())
        Command.ListObjects -> CommandResult.Ok(objects.values.map { it.obj.root.snapshot() })
        is Command.Inspect -> handleInspect(command)
        is Command.AnalyzeGraph -> handleAnalyzeGraph(command)
        is Command.Delete -> handleDelete(command)
    }

    private fun handleCreate(cmd: Command.Create): CommandResult {
        if (!ObjectId.isValid(cmd.id)) return invalidId(cmd.id)
        val id = ObjectId(cmd.id)
        if (id in objects) return rejected(ErrorCode.DUPLICATE_ID, "object '${cmd.id}' already exists")
        val type = registry.find(cmd.type) ?: return rejected(ErrorCode.UNKNOWN_TYPE, "unknown type '${cmd.type}'")

        val validated = validateProperties(type.schema.properties, cmd.props)
        if (!validated.isValid) return CommandResult.Rejected(validated.errors.toCommandError())

        val obj = type.create(id, validated.properties, ctx)
        objects[id] = Registered(type, obj, validated.properties)
        events.emit(id, EventPayload.ObjectCreated(type.schema.name, type.schema.kind))
        return CommandResult.Ok(obj.root.snapshot())
    }

    /**
     * Validates [cmd.commands] as a whole against a shadow state (no real object is created,
     * no event is emitted) before applying any of it for real. If shadow validation passes, each
     * sub-command is guaranteed to succeed when replayed through [handle], so no rollback is needed.
     * Only Create and Connect are supported inside a batch -- that's all a topology ever needs.
     */
    private fun handleApplyTopology(cmd: Command.ApplyTopology): CommandResult {
        validateTopologyBatch(cmd.commands)?.let { return CommandResult.Rejected(it) }
        val results = cmd.commands.map { sub ->
            when (val r = handle(sub)) {
                is CommandResult.Ok -> r
                is CommandResult.Rejected ->
                    error("unreachable: '$sub' passed shadow validation but was rejected for real: ${r.error}")
            }
        }
        return CommandResult.Ok(results.map { it.data })
    }

    private fun validateTopologyBatch(commands: List<Command>): CommandError? {
        val shadowKinds = mutableMapOf<ObjectId, ObjectKind>()
        val shadowEndpointMedia = mutableMapOf<String, MediaType>()
        val shadowConnections = mutableMapOf<ObjectId, Pair<String, String>>()

        fun kindOf(id: ObjectId): ObjectKind? = shadowKinds[id] ?: objects[id]?.type?.schema?.kind
        fun mediaOf(endpoint: String): MediaType? = shadowEndpointMedia[endpoint]
            ?: objects.values.asSequence().flatMap { it.obj.endpoints }.firstOrNull { it.id.value == endpoint }?.media
        fun connectionOf(cableId: ObjectId): Pair<String, String>? = shadowConnections[cableId]
            ?: objects[cableId]?.obj?.cable?.endpoints?.let { it.first.id.value to it.second.id.value }
        fun occupied(endpoint: String): Boolean =
            shadowConnections.values.any { endpoint == it.first || endpoint == it.second } ||
                objects.values.any { r -> r.obj.cable?.endpoints?.let { endpoint == it.first.id.value || endpoint == it.second.id.value } == true }

        for (sub in commands) {
            when (sub) {
                is Command.Create -> {
                    if (!ObjectId.isValid(sub.id)) return CommandError(ErrorCode.INVALID_ID, "invalid object id '${sub.id}'")
                    val id = ObjectId(sub.id)
                    if (id in objects || id in shadowKinds) return CommandError(ErrorCode.DUPLICATE_ID, "object '${sub.id}' already exists")
                    val type = registry.find(sub.type) ?: return CommandError(ErrorCode.UNKNOWN_TYPE, "unknown type '${sub.type}'")
                    val validated = validateProperties(type.schema.properties, sub.props)
                    if (!validated.isValid) return validated.errors.toCommandError()

                    shadowKinds[id] = type.schema.kind
                    type.schema.interfaces.forEach { shadowEndpointMedia[id.child(it.name).value] = it.media }
                }
                is Command.Connect -> {
                    if (!ObjectId.isValid(sub.cableId)) return CommandError(ErrorCode.INVALID_ID, "invalid object id '${sub.cableId}'")
                    val cableId = ObjectId(sub.cableId)
                    if (kindOf(cableId) == null) return CommandError(ErrorCode.UNKNOWN_OBJECT, "no object with id '${sub.cableId}'")
                    if (kindOf(cableId) != ObjectKind.CABLE) return CommandError(ErrorCode.NOT_A_CABLE, "'${sub.cableId}' is not a cable")

                    val a = sub.a.value
                    val b = sub.b.value
                    val aMedia = mediaOf(a) ?: return CommandError(ErrorCode.UNKNOWN_ENDPOINT, "unknown endpoint '$a'")
                    val bMedia = mediaOf(b) ?: return CommandError(ErrorCode.UNKNOWN_ENDPOINT, "unknown endpoint '$b'")

                    val current = connectionOf(cableId)
                    if (current != null) {
                        if (setOf(current.first, current.second) != setOf(a, b)) {
                            return CommandError(ErrorCode.CABLE_OCCUPIED, "cable '${sub.cableId}' is already connected to a different pair")
                        }
                    } else {
                        if (a == b) return CommandError(ErrorCode.SELF_CONNECTION, "cannot connect an endpoint to itself", mapOf("endpoint" to a))
                        if (aMedia != bMedia) return CommandError(ErrorCode.INCOMPATIBLE_MEDIA, "incompatible media: $aMedia vs $bMedia")
                        if (occupied(a)) return CommandError(ErrorCode.ENDPOINT_OCCUPIED, "endpoint '$a' is occupied", mapOf("endpoint" to a))
                        if (occupied(b)) return CommandError(ErrorCode.ENDPOINT_OCCUPIED, "endpoint '$b' is occupied", mapOf("endpoint" to b))
                        shadowConnections[cableId] = a to b
                    }
                }
                else -> return CommandError(ErrorCode.INVALID_REQUEST, "unsupported command in ApplyTopology batch: $sub")
            }
        }
        return null
    }

    private fun handleConnect(cmd: Command.Connect): CommandResult {
        if (!ObjectId.isValid(cmd.cableId)) return invalidId(cmd.cableId)
        val registered = objects[ObjectId(cmd.cableId)] ?: return unknownObject(cmd.cableId)
        val cable = registered.obj.cable ?: return rejected(ErrorCode.NOT_A_CABLE, "'${cmd.cableId}' is not a cable")

        val a = resolveEndpoint(cmd.a) ?: return rejected(ErrorCode.UNKNOWN_ENDPOINT, "unknown endpoint '${cmd.a}'")
        val b = resolveEndpoint(cmd.b) ?: return rejected(ErrorCode.UNKNOWN_ENDPOINT, "unknown endpoint '${cmd.b}'")

        val current = cable.endpoints
        if (current != null) {
            val samePair = setOf(current.first.id, current.second.id) == setOf(a.id, b.id)
            return if (samePair) CommandResult.Ok(changed = false)
            else rejected(ErrorCode.CABLE_OCCUPIED, "cable '${cmd.cableId}' is already connected to a different pair")
        }

        // cable.isConnected is false here, so ConnectionProblem.ALREADY_CONNECTED cannot occur:
        // every other ConnectionProblem name matches an ErrorCode of the same name.
        cable.connectionProblem(a, b)?.let {
            return rejected(ErrorCode.valueOf(it.problem.name), "cannot connect: ${it.problem}", it.endpoint?.let { ep -> mapOf("endpoint" to ep.value) } ?: emptyMap())
        }

        cable.connect(a, b)
        return CommandResult.Ok(changed = true)
    }

    private fun handleDisconnect(cmd: Command.Disconnect): CommandResult {
        if (!ObjectId.isValid(cmd.cableId)) return invalidId(cmd.cableId)
        val registered = objects[ObjectId(cmd.cableId)] ?: return unknownObject(cmd.cableId)
        val cable = registered.obj.cable ?: return rejected(ErrorCode.NOT_A_CABLE, "'${cmd.cableId}' is not a cable")
        if (!cable.isConnected) return CommandResult.Ok(changed = false)
        cable.disconnect()
        return CommandResult.Ok(changed = true)
    }

    private fun handleConfigure(cmd: Command.Configure): CommandResult {
        if (!ObjectId.isValid(cmd.id)) return invalidId(cmd.id)
        val id = ObjectId(cmd.id)
        val registered = objects[id] ?: return unknownObject(cmd.id)
        if (cmd.props.isEmpty()) return CommandResult.Ok(changed = false)
        val schemaByName = registered.type.schema.properties.associateBy { it.name }
        cmd.props.keys.forEach { key ->
            val spec = schemaByName[key]
                ?: return rejected(ErrorCode.UNKNOWN_PROPERTY, "unknown property '$key'")
            if (!spec.mutable) return rejected(ErrorCode.IMMUTABLE_PROPERTY, "property '$key' is not mutable")
        }

        // Validate the complete merged property set before touching power, links, or registry.
        // This also enforces cross-version defaults consistently with Create.
        val merged = registered.properties + cmd.props
        val validated = validateProperties(registered.type.schema.properties, merged)
        if (!validated.isValid) return CommandResult.Rejected(validated.errors.toCommandError())
        val changed = cmd.props.keys.associateWith { validated.properties[it] }
        if (validated.properties == registered.properties) return CommandResult.Ok(changed = false)

        val previousPower = registered.obj.power?.powerState
        val connections = objects.mapNotNull { (cableId, candidate) ->
            val endpoints = candidate.obj.cable?.endpoints ?: return@mapNotNull null
            if (candidate === registered || endpoints.first in registered.obj.endpoints || endpoints.second in registered.obj.endpoints) {
                Triple(cableId, endpoints.first.id, endpoints.second.id)
            } else null
        }
        val replacement = registered.type.create(id, validated.properties, ctx)

        // Commit only after validation and construction have succeeded. Unplugging first ensures
        // stale in-flight frames and link callbacks cannot enter the replacement object.
        connections.forEach { (cableId, _, _) -> objects.getValue(cableId).obj.cable!!.disconnect() }
        registered.obj.power?.powerOff()
        registered.obj = replacement
        registered.properties = validated.properties
        connections.forEach { (cableId, aId, bId) ->
            val cable = objects.getValue(cableId).obj.cable!!
            val a = resolveEndpoint(EndpointRef(aId.value))!!
            val b = resolveEndpoint(EndpointRef(bId.value))!!
            cable.connect(a, b)
        }
        if (previousPower != null && previousPower != com.a2z.nsdl.model.PowerState.OFF) {
            replacement.power!!.powerOn()
        }
        events.emit(id, EventPayload.ConfigurationChanged(changed))
        return CommandResult.Ok(changed = true)
    }

    private fun handlePower(rawId: String, on: Boolean): CommandResult {
        if (!ObjectId.isValid(rawId)) return invalidId(rawId)
        val registered = objects[ObjectId(rawId)] ?: return unknownObject(rawId)
        val power = registered.obj.power ?: return rejected(ErrorCode.NOT_POWERABLE, "'$rawId' cannot be powered")
        val change = if (on) power.powerOn() else power.powerOff()
        return CommandResult.Ok(changed = change == PowerChange.CHANGED)
    }

    private fun handleAdvance(cmd: Command.Advance): CommandResult {
        if (cmd.duration.isNegative()) return rejected(ErrorCode.INVALID_REQUEST, "duration must not be negative")
        if (cmd.duration > limits.maxAdvanceDuration) {
            return rejected(ErrorCode.LIMIT_EXCEEDED, "requested duration exceeds the configured limit of ${limits.maxAdvanceDuration}")
        }
        val result = scheduler.advanceBy(cmd.duration, limits.maxEventsPerAdvance)
        return CommandResult.Ok(
            mapOf("nowMs" to result.now.millis, "eventsProcessed" to result.eventsProcessed, "truncated" to result.truncated),
        )
    }

    /** A registered object's root is inspectable by its own id; its components (interfaces, services) by theirs. */
    private fun handleInspect(cmd: Command.Inspect): CommandResult {
        if (!ObjectId.isValid(cmd.id)) return invalidId(cmd.id)
        val id = ObjectId(cmd.id)
        val target = objects[id]?.obj?.root
            ?: objects.values.asSequence().flatMap { it.obj.components }.firstOrNull { it.id == id }
        return target?.let { CommandResult.Ok(it.snapshot()) } ?: unknownObject(cmd.id)
    }

    /**
     * Builds an undirected graph from the current physical topology. Simulation devices are
     * vertices and connected cable objects are edges; disconnected cables and protocol state do
     * not affect the result. All collections are sorted so the wire response is deterministic.
     */
    private fun handleAnalyzeGraph(cmd: Command.AnalyzeGraph): CommandResult {
        if (!ObjectId.isValid(cmd.id)) return invalidId(cmd.id)
        val selectedId = ObjectId(cmd.id)
        val selected = objects[selectedId] ?: return unknownObject(cmd.id)
        if (selected.obj.cable != null) {
            return rejected(ErrorCode.INVALID_REQUEST, "'${cmd.id}' is a cable, not a graph node")
        }

        val nodes = objects.filterValues { it.obj.cable == null }.keys
        val endpointOwners = buildMap {
            objects.forEach { (objectId, registered) ->
                registered.obj.endpoints.forEach { endpoint -> put(endpoint.id, objectId) }
            }
        }
        val adjacency = nodes.associateWith { mutableSetOf<ObjectId>() }
        objects.values.forEach { registered ->
            val (a, b) = registered.obj.cable?.endpoints ?: return@forEach
            val ownerA = endpointOwners[a.id] ?: return@forEach
            val ownerB = endpointOwners[b.id] ?: return@forEach
            if (ownerA != ownerB) {
                adjacency.getValue(ownerA).add(ownerB)
                adjacency.getValue(ownerB).add(ownerA)
            }
        }

        val distances = breadthFirstDistances(selectedId, adjacency)
        val component = distances.keys
        val articulationPoint = if (component.size <= 2) {
            false
        } else {
            val start = component.first { it != selectedId }
            breadthFirstDistances(start, adjacency, excluded = selectedId).keys.size != component.size - 1
        }

        return CommandResult.Ok(
            mapOf(
                "nodeId" to selectedId.value,
                "connectedComponent" to component.map { it.value }.sorted(),
                "degree" to adjacency.getValue(selectedId).size,
                "shortestPaths" to distances.entries.sortedBy { it.key.value }.associate { it.key.value to it.value },
                "articulationPoint" to articulationPoint,
            ),
            changed = false,
        )
    }

    private fun breadthFirstDistances(
        start: ObjectId,
        adjacency: Map<ObjectId, Set<ObjectId>>,
        excluded: ObjectId? = null,
    ): Map<ObjectId, Int> {
        if (start == excluded) return emptyMap()
        val distances = linkedMapOf(start to 0)
        val queue = ArrayDeque<ObjectId>().apply { add(start) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            adjacency.getValue(current).asSequence()
                .filter { it != excluded && it !in distances }
                .sortedBy { it.value }
                .forEach { neighbor ->
                    distances[neighbor] = distances.getValue(current) + 1
                    queue.add(neighbor)
                }
        }
        return distances
    }

    /**
     * Deleting a device atomically deletes every cable attached to any of its endpoints too --
     * those cables are genuinely removed, not just disconnected. Deleting a cable just disconnects
     * and removes itself. A powered device is powered off first so its lifecycle (WorkScope,
     * timers) cleans up through the same path a normal power-off uses.
     */
    private fun handleDelete(cmd: Command.Delete): CommandResult {
        if (!ObjectId.isValid(cmd.id)) return invalidId(cmd.id)
        val id = ObjectId(cmd.id)
        val registered = objects[id] ?: return unknownObject(cmd.id)

        val cascadedCableIds = if (registered.obj.cable == null) {
            val ownEndpointIds = registered.obj.endpoints.map { it.id }.toSet()
            objects.filterValues { r ->
                r.obj.cable?.endpoints?.let { (a, b) -> a.id in ownEndpointIds || b.id in ownEndpointIds } == true
            }.keys.toList()
        } else {
            emptyList()
        }

        registered.obj.power?.powerOff()
        cascadedCableIds.forEach { deleteOne(it) }
        deleteOne(id)

        return CommandResult.Ok(mapOf("deleted" to (listOf(id.value) + cascadedCableIds.map { it.value })))
    }

    private fun deleteOne(id: ObjectId) {
        val registered = objects.remove(id) ?: return
        registered.obj.cable?.let { if (it.isConnected) it.disconnect() }
        events.emit(id, EventPayload.ObjectDeleted(registered.type.schema.name, registered.type.schema.kind))
    }

    private fun resolveEndpoint(ref: EndpointRef): LinkEndpoint? =
        objects.values.asSequence().flatMap { it.obj.endpoints }.firstOrNull { it.id.value == ref.value }

    private fun invalidId(id: String) = rejected(ErrorCode.INVALID_ID, "invalid object id '$id'")
    private fun unknownObject(id: String) = rejected(ErrorCode.UNKNOWN_OBJECT, "no object with id '$id'")
    private fun rejected(code: ErrorCode, message: String, details: Map<String, Any?> = emptyMap()) =
        CommandResult.Rejected(CommandError(code, message, details))

    private fun List<PropertyError>.toCommandError(): CommandError {
        val first = first()
        return CommandError(
            code = ErrorCode.valueOf(first.problem.name),
            message = first.message,
            details = mapOf("errors" to map { mapOf("property" to it.property, "code" to it.problem.name, "message" to it.message) }),
        )
    }
}
