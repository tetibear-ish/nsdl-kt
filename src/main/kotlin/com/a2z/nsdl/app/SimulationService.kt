package com.a2z.nsdl.app

import com.a2z.nsdl.device.PowerChange
import com.a2z.nsdl.link.LinkEndpoint
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.ObjectId
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
    private data class Registered(val type: ObjectType, val obj: SimObject)

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
        is Command.Connect -> handleConnect(command)
        is Command.Disconnect -> handleDisconnect(command)
        is Command.Configure -> handleConfigure(command)
        is Command.PowerOn -> handlePower(command.id, on = true)
        is Command.PowerOff -> handlePower(command.id, on = false)
        is Command.Advance -> handleAdvance(command)
        Command.ListTypes -> CommandResult.Ok(registry.list())
        Command.ListObjects -> CommandResult.Ok(objects.values.map { it.obj.root.snapshot() })
        is Command.Inspect -> handleInspect(command)
    }

    private fun handleCreate(cmd: Command.Create): CommandResult {
        if (!ObjectId.isValid(cmd.id)) return invalidId(cmd.id)
        val id = ObjectId(cmd.id)
        if (id in objects) return rejected(ErrorCode.DUPLICATE_ID, "object '${cmd.id}' already exists")
        val type = registry.find(cmd.type) ?: return rejected(ErrorCode.UNKNOWN_TYPE, "unknown type '${cmd.type}'")

        val validated = validateProperties(type.schema.properties, cmd.props)
        if (!validated.isValid) return CommandResult.Rejected(validated.errors.toCommandError())

        val obj = type.create(id, validated.properties, ctx)
        objects[id] = Registered(type, obj)
        events.emit(id, EventPayload.ObjectCreated(type.schema.name, type.schema.kind))
        return CommandResult.Ok(obj.root.snapshot())
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
        val schema = registered.type.schema.properties.associateBy { it.name }

        val applied = mutableMapOf<String, Any?>()
        for ((key, raw) in cmd.props) {
            val spec = schema[key] ?: return rejected(ErrorCode.UNKNOWN_PROPERTY, "unknown property '$key'")
            if (!spec.mutable) return rejected(ErrorCode.IMMUTABLE_PROPERTY, "property '$key' is not mutable")
            val coerced = raw?.let { coerce(spec.type, it) }
            if (coerced == null) return rejected(ErrorCode.INVALID_PROPERTY, "invalid value for '$key': $raw")
            applied[key] = coerced
        }
        if (applied.isEmpty()) return CommandResult.Ok(changed = false)

        applied.forEach { (key, value) -> registered.obj.configure(key, value) }
        events.emit(id, EventPayload.ConfigurationChanged(applied))
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

    private fun handleInspect(cmd: Command.Inspect): CommandResult {
        if (!ObjectId.isValid(cmd.id)) return invalidId(cmd.id)
        val registered = objects[ObjectId(cmd.id)] ?: return unknownObject(cmd.id)
        return CommandResult.Ok(registered.obj.root.snapshot())
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
