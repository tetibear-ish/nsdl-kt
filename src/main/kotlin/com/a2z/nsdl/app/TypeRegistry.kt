package com.a2z.nsdl.app

/** Registered [ObjectType]s, keyed by [ObjectTypeSchema.name]. */
class TypeRegistry {
    private val types = mutableMapOf<String, ObjectType>()

    fun register(type: ObjectType) {
        val name = type.schema.name
        require(name !in types) { "object type '$name' is already registered" }
        types[name] = type
    }

    fun find(name: String): ObjectType? = types[name]

    fun list(): List<ObjectTypeSchema> = types.values.map { it.schema }
}
