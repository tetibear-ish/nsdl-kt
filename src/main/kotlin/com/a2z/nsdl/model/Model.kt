package com.a2z.nsdl.model

/** Stable, human-readable identity of a simulation object (device, interface, cable, protocol component). */
@JvmInline
value class ObjectId(val value: String) {
    init { require(value.matches(PATTERN)) { "invalid object id '$value'" } }
    fun child(name: String) = ObjectId("$value.$name")
    override fun toString() = value

    companion object {
        val PATTERN = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")
        fun isValid(value: String) = value.matches(PATTERN)
    }
}

enum class ObjectKind { DEVICE, INTERFACE, CABLE, PROTOCOL }

enum class PowerState { OFF, BOOTING, ON }

/**
 * Read-only, serializable view of an object's modeled state. Values in [state] are restricted to
 * JSON-compatible types: String, Number, Boolean, null, List and Map of those.
 * Relationships to other objects are expressed as IDs in [relations].
 */
data class ObjectSnapshot(
    val id: ObjectId,
    val type: String,
    val kind: ObjectKind,
    val state: Map<String, Any?>,
    val relations: Map<String, List<ObjectId>> = emptyMap(),
)

/** Inspection contract. Used by external observers only; simulated behavior never reads it. */
interface Inspectable {
    val id: ObjectId
    fun snapshot(): ObjectSnapshot
}

/** How domain components report observable facts. The runtime stamps time, sequence and correlation. */
fun interface EventSink {
    fun emit(source: ObjectId, payload: EventPayload)
}
