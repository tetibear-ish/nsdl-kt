package com.a2z.nsdl.app

import com.a2z.nsdl.app.types.Cat5CableType
import com.a2z.nsdl.app.types.DhcpServerHostType
import com.a2z.nsdl.app.types.PrinterType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypeRegistryTest {
    private val registry = TypeRegistry()

    @Test
    fun `registered types are found by name and listed with their schemas`() {
        registry.register(PrinterType)
        registry.register(DhcpServerHostType)
        registry.register(Cat5CableType)

        assertEquals(PrinterType, registry.find("printer"))
        assertEquals(DhcpServerHostType, registry.find("gateway"))
        assertEquals(Cat5CableType, registry.find("cat5-cable"))
        assertEquals(
            setOf("printer", "gateway", "cat5-cable"),
            registry.list().map { it.name }.toSet(),
        )
        assertTrue(registry.list().contains(PrinterType.schema))
    }

    @Test
    fun `an unknown type name is not found`() {
        assertNull(registry.find("nope"))
    }

    @Test
    fun `registering a duplicate name throws`() {
        registry.register(PrinterType)
        assertThrows(IllegalArgumentException::class.java) { registry.register(PrinterType) }
    }
}
