package com.a2z.nsdl.nsdl

/** The boundary a future topology-file parser (JSON, YAML, ...) implements to produce a [TopologySpec]. */
fun interface TopologySource {
    fun read(): TopologySpec
}
