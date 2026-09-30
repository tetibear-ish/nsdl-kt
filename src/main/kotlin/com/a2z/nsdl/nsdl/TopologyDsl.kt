package com.a2z.nsdl.nsdl

/** A small programmatic builder for [TopologySpec], for building one in Kotlin without a parser. */
class TopologyBuilder {
    private val objects = mutableListOf<TopologyObjectSpec>()
    private val connections = mutableListOf<TopologyConnectionSpec>()

    fun create(id: String, type: String, props: Map<String, Any?> = emptyMap()) {
        objects += TopologyObjectSpec(id, type, props)
    }

    fun connect(cableId: String, a: String, b: String) {
        connections += TopologyConnectionSpec(cableId, a, b)
    }

    fun build(): TopologySpec = TopologySpec(objects.toList(), connections.toList())
}

fun topology(block: TopologyBuilder.() -> Unit): TopologySpec = TopologyBuilder().apply(block).build()
