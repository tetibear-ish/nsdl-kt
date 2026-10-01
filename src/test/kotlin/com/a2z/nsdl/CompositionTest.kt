package com.a2z.nsdl

import com.a2z.nsdl.app.Command
import com.a2z.nsdl.app.CommandResult
import com.a2z.nsdl.app.ObjectTypeSchema
import com.a2z.nsdl.events.Delivery
import com.a2z.nsdl.events.EventFilter
import com.a2z.nsdl.events.SubscribeResult
import com.a2z.nsdl.model.EventPayload.BootCompleted
import com.a2z.nsdl.runtime.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class CompositionTest {
    private var composition: Composition? = null

    private fun newComposition(seed: Long = 0L) = Composition(randomSeed = seed).also { composition = it }

    @AfterEach
    fun tearDown() {
        composition?.close()
    }

    @Test
    fun `the composed runtime registers all built-in network object types`() {
        val c = newComposition()
        val result = c.runtime.submit(Request(Command.ListTypes))

        assertTrue(result.result is CommandResult.Ok)
        @Suppress("UNCHECKED_CAST")
        val names = ((result.result as CommandResult.Ok).data as List<ObjectTypeSchema>).map { it.name }.toSet()
        assertEquals(setOf("computer", "printer", "gateway", "ethernet-switch", "cat5-cable"), names)
    }

    @Test
    fun `events are stamped with the scheduler's virtual time, not a constant or wall-clock time`() {
        val c = newComposition()
        c.runtime.submit(Request(Command.Create("printer1", "printer", mapOf("bootMs" to 3000L))))
        val sub = (c.runtime.subscribe(EventFilter(types = setOf("BootCompleted")), from = 0, capacity = 10) as SubscribeResult.Subscribed).subscription

        c.runtime.submit(Request(Command.PowerOn("printer1")))
        c.runtime.submit(Request(Command.Advance(3.seconds)))

        val record = (sub.poll() as Delivery.Event).record
        assertEquals(BootCompleted(1), record.payload)
        assertEquals(3000L, record.timeMs)
    }
}
