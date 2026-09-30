package com.a2z.nsdl.app

import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.MacAddress

enum class PropertyProblem { MISSING_PROPERTY, UNKNOWN_PROPERTY, INVALID_PROPERTY }

data class PropertyError(val problem: PropertyProblem, val property: String, val message: String)

/** [properties] holds only the properties from [schema]; unknown input keys are reported as errors, not carried through. */
data class PropertyValidation(val properties: Map<String, Any?>, val errors: List<PropertyError>) {
    val isValid get() = errors.isEmpty()
}

/**
 * Validates [input] against [schema], filling in defaults for absent properties and coercing
 * provided values to their declared [PropertyType]. A missing key and an explicit null value are
 * both treated as "absent". Pure: collects every error rather than stopping at the first one.
 */
fun validateProperties(schema: List<PropertySpec>, input: Map<String, Any?>): PropertyValidation {
    val errors = mutableListOf<PropertyError>()
    val byName = schema.associateBy { it.name }

    for (key in input.keys) {
        if (key !in byName) errors += PropertyError(PropertyProblem.UNKNOWN_PROPERTY, key, "unknown property '$key'")
    }

    val result = mutableMapOf<String, Any?>()
    for (spec in schema) {
        val raw = input[spec.name]
        when {
            raw != null -> {
                val coerced = coerce(spec.type, raw)
                if (coerced == null) {
                    errors += PropertyError(PropertyProblem.INVALID_PROPERTY, spec.name, "invalid value for '${spec.name}': $raw")
                } else {
                    result[spec.name] = coerced
                }
            }
            spec.required -> errors += PropertyError(PropertyProblem.MISSING_PROPERTY, spec.name, "missing required property '${spec.name}'")
            else -> result[spec.name] = spec.default
        }
    }
    return PropertyValidation(result, errors)
}

private fun coerce(type: PropertyType, value: Any): Any? = when (type) {
    PropertyType.STRING -> value as? String
    PropertyType.LONG -> when (value) {
        is Long -> value
        is Int -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
    PropertyType.IPV4 -> when (value) {
        is Ipv4Address -> value
        is String -> runCatching { Ipv4Address.parse(value) }.getOrNull()
        else -> null
    }
    PropertyType.MAC -> when (value) {
        is MacAddress -> value
        is String -> runCatching { MacAddress.parse(value) }.getOrNull()
        else -> null
    }
    PropertyType.LINK_PROFILE -> when (value) {
        is LinkProfile -> value
        is String -> LinkProfile.SUPPORTED[value]
        else -> null
    }
}
