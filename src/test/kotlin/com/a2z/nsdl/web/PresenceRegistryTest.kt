package com.a2z.nsdl.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PresenceRegistryTest {
    @Test
    fun `a fresh registry has no clients`() {
        val registry = PresenceRegistry()

        assertTrue(registry.current().isEmpty())
    }

    @Test
    fun `joining adds the client to the roster`() {
        val registry = PresenceRegistry()

        registry.join("client-a")

        assertEquals(setOf("client-a"), registry.current())
    }

    @Test
    fun `leaving removes the client from the roster`() {
        val registry = PresenceRegistry()
        registry.join("client-a")

        registry.leave("client-a")

        assertTrue(registry.current().isEmpty())
    }

    @Test
    fun `a client open in two tabs stays present until every tab leaves`() {
        val registry = PresenceRegistry()
        registry.join("client-a")
        registry.join("client-a") // a second tab

        registry.leave("client-a") // first tab closes
        assertEquals(setOf("client-a"), registry.current(), "still present: the second tab's connection is open")

        registry.leave("client-a") // second tab closes
        assertTrue(registry.current().isEmpty())
    }

    @Test
    fun `leaving a client that was never present is a no-op`() {
        val registry = PresenceRegistry()

        registry.leave("ghost")

        assertTrue(registry.current().isEmpty())
    }

    @Test
    fun `listeners are notified with the current roster on join and leave`() {
        val registry = PresenceRegistry()
        val seen = mutableListOf<Set<String>>()
        registry.onChange { seen += it }

        registry.join("client-a")
        registry.join("client-b")
        registry.leave("client-a")

        assertEquals(
            listOf(setOf("client-a"), setOf("client-a", "client-b"), setOf("client-b")),
            seen,
        )
    }

    @Test
    fun `unsubscribing stops further notifications`() {
        val registry = PresenceRegistry()
        val seen = mutableListOf<Set<String>>()
        val unsubscribe = registry.onChange { seen += it }

        registry.join("client-a")
        unsubscribe()
        registry.join("client-b")

        assertEquals(listOf(setOf("client-a")), seen)
    }
}
