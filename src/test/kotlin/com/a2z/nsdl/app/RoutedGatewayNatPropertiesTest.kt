package com.a2z.nsdl.app

import com.a2z.nsdl.Composition
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.runtime.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoutedGatewayNatPropertiesTest {
    private val composition = Composition(randomSeed = 1)

    @AfterEach
    fun close() = composition.close()

    private fun submit(command: Command) = composition.runtime.submit(Request(command)).result

    @Suppress("UNCHECKED_CAST")
    private fun natState(): Map<String, Any?>? =
        ((submit(Command.Inspect("gw.router")) as CommandResult.Ok).data as ObjectSnapshot).state["nat"] as Map<String, Any?>?

    @Test
    fun `nat and port forwards are configured from text properties`() {
        val created = submit(
            Command.Create(
                "gw", "routed-gateway",
                mapOf("wanAddress" to "203.0.113.1", "nat" to "true", "portForwards" to "8080>192.168.1.50:80"),
            ),
        )

        assertTrue(created is CommandResult.Ok, "$created")
        val nat = natState()!!
        assertEquals("203.0.113.1", nat["outsideAddress"])
        @Suppress("UNCHECKED_CAST")
        val mapping = (nat["mappings"] as List<Map<String, Any?>>).single()
        assertEquals("PORT_FORWARD", mapping["kind"])
        assertEquals("192.168.1.50:80", mapping["inside"])
    }

    @Test
    fun `nat stays off by default and without a wan address`() {
        submit(Command.Create("gw", "routed-gateway", mapOf("nat" to true)))

        assertNull(natState())
    }

    @Test
    fun `a malformed port forward is rejected as an invalid property`() {
        val result = submit(Command.Create("gw", "routed-gateway", mapOf("wanAddress" to "203.0.113.1", "portForwards" to "8080>nowhere")))

        assertTrue(result is CommandResult.Rejected)
        assertEquals(ErrorCode.INVALID_PROPERTY, (result as CommandResult.Rejected).error.code)
    }
}
