package com.a2z.nsdl.app

import com.a2z.nsdl.app.types.Cat5CableType
import com.a2z.nsdl.app.types.DhcpServerHostType
import com.a2z.nsdl.app.types.PrinterType
import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.net.MacAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PropertyValidatorTest {

    @Test
    fun `absent optional properties are filled with their declared defaults`() {
        val result = validateProperties(PrinterType.schema.properties, emptyMap())

        assertTrue(result.isValid, "errors: ${result.errors}")
        assertEquals(3000L, result.properties["bootMs"])
        assertEquals(null, result.properties["mac"])
    }

    @Test
    fun `a provided value is coerced from its wire representation`() {
        val result = validateProperties(PrinterType.schema.properties, mapOf("bootMs" to "1500", "mac" to "02:00:00:00:00:05"))

        assertTrue(result.isValid, "errors: ${result.errors}")
        assertEquals(1500L, result.properties["bootMs"])
        assertEquals(MacAddress.parse("02:00:00:00:00:05"), result.properties["mac"])
    }

    @Test
    fun `a missing required property is reported as MISSING_PROPERTY`() {
        val result = validateProperties(DhcpServerHostType.schema.properties, emptyMap())

        assertEquals(
            setOf("address", "poolStart", "poolEnd"),
            result.errors.filter { it.problem == PropertyProblem.MISSING_PROPERTY }.map { it.property }.toSet(),
        )
    }

    @Test
    fun `an unrecognized property key is reported as UNKNOWN_PROPERTY and not carried into the result`() {
        val result = validateProperties(
            PrinterType.schema.properties,
            mapOf("bootMs" to 2000L, "color" to "beige"),
        )

        assertEquals(
            listOf(PropertyError(PropertyProblem.UNKNOWN_PROPERTY, "color", "unknown property 'color'")),
            result.errors,
        )
        assertTrue("color" !in result.properties)
    }

    @Test
    fun `a malformed IPv4 address is reported as INVALID_PROPERTY`() {
        val result = validateProperties(
            DhcpServerHostType.schema.properties,
            mapOf("address" to "999.0.0.1", "poolStart" to "10.0.0.100", "poolEnd" to "10.0.0.110"),
        )

        assertEquals(
            listOf(PropertyError(PropertyProblem.INVALID_PROPERTY, "address", "invalid value for 'address': 999.0.0.1")),
            result.errors,
        )
    }

    @Test
    fun `an unknown cable profile is reported as INVALID_PROPERTY, not inferred from the type name`() {
        val result = validateProperties(Cat5CableType.schema.properties, mapOf("profile" to "CAT5"))

        assertEquals(
            listOf(PropertyError(PropertyProblem.INVALID_PROPERTY, "profile", "invalid value for 'profile': CAT5")),
            result.errors,
        )
    }

    @Test
    fun `a recognized cable profile is accepted`() {
        val result = validateProperties(Cat5CableType.schema.properties, mapOf("profile" to "100BASE-TX"))

        assertTrue(result.isValid, "errors: ${result.errors}")
        assertEquals(LinkProfile.FAST_ETHERNET_100BASE_TX, result.properties["profile"])
    }

    @Test
    fun `an absent cable profile defaults to 100BASE-TX`() {
        val result = validateProperties(Cat5CableType.schema.properties, emptyMap())

        assertTrue(result.isValid, "errors: ${result.errors}")
        assertEquals(LinkProfile.FAST_ETHERNET_100BASE_TX, result.properties["profile"])
    }

    @Test
    fun `errors are collected together rather than stopping at the first one`() {
        val result = validateProperties(
            DhcpServerHostType.schema.properties,
            mapOf("address" to "not-an-ip", "extra" to "nope"),
        )

        assertEquals(
            setOf("address" to PropertyProblem.INVALID_PROPERTY, "poolStart" to PropertyProblem.MISSING_PROPERTY, "poolEnd" to PropertyProblem.MISSING_PROPERTY, "extra" to PropertyProblem.UNKNOWN_PROPERTY),
            result.errors.map { it.property to it.problem }.toSet(),
        )
    }
}
