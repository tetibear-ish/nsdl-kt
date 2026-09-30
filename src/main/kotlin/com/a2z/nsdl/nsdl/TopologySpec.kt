package com.a2z.nsdl.nsdl

import com.a2z.nsdl.app.Command
import com.a2z.nsdl.app.EndpointRef

data class TopologyObjectSpec(val id: String, val type: String, val props: Map<String, Any?> = emptyMap())

data class TopologyConnectionSpec(val cableId: String, val a: String, val b: String)

/** A batch of objects and cable connections to bring into existence together. */
data class TopologySpec(
    val objects: List<TopologyObjectSpec> = emptyList(),
    val connections: List<TopologyConnectionSpec> = emptyList(),
)

/** Translates this spec into the [Command.ApplyTopology] batch [com.a2z.nsdl.app.SimulationService] applies atomically. */
fun TopologySpec.toCommand(): Command.ApplyTopology = Command.ApplyTopology(
    objects.map { Command.Create(it.id, it.type, it.props) } +
        connections.map { Command.Connect(it.cableId, EndpointRef(it.a), EndpointRef(it.b)) },
)
