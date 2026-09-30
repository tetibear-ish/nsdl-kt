package com.a2z.nsdl.app

import kotlin.time.Duration

/** Reference to an interface exposed by a created object, e.g. "printer.eth0". */
data class EndpointRef(val value: String) {
    override fun toString() = value
}

/**
 * A request to [SimulationService]. Ids and type names are raw strings, not [com.a2z.nsdl.model.ObjectId]:
 * a command must be constructible from untrusted input so validation can report a structured
 * error instead of throwing.
 */
sealed interface Command {
    data class Create(val id: String, val type: String, val props: Map<String, Any?> = emptyMap()) : Command
    /** Applied atomically: [commands] is validated as a whole against a shadow state before any of it runs. */
    data class ApplyTopology(val commands: List<Command>) : Command
    data class Connect(val cableId: String, val a: EndpointRef, val b: EndpointRef) : Command
    data class Disconnect(val cableId: String) : Command
    data class Configure(val id: String, val props: Map<String, Any?>) : Command
    data class PowerOn(val id: String) : Command
    data class PowerOff(val id: String) : Command
    data class Advance(val duration: Duration) : Command
    data object ListTypes : Command
    data object ListObjects : Command
    data class Inspect(val id: String) : Command
    /** Deleting a device atomically deletes every cable attached to any of its endpoints too. */
    data class Delete(val id: String) : Command
}
