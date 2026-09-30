package com.a2z.nsdl.nsdl

import com.a2z.nsdl.app.Command
import com.a2z.nsdl.app.CommandResult
import com.a2z.nsdl.app.EndpointRef
import com.a2z.nsdl.app.ErrorCode
import com.a2z.nsdl.app.SimulationService
import com.a2z.nsdl.app.TypeRegistry
import com.a2z.nsdl.app.types.Cat5CableType
import com.a2z.nsdl.app.types.DhcpServerHostType
import com.a2z.nsdl.app.types.PrinterType
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TopologyTest {
    private val scheduler = VirtualScheduler()
    private val sink = RecordingSink()
    private val registry = TypeRegistry().apply {
        register(PrinterType)
        register(DhcpServerHostType)
        register(Cat5CableType)
    }
    private val service = SimulationService(scheduler, sink, registry)

    private fun validTopology() = topology {
        create("printer1", "printer")
        create("server1", "gateway", mapOf("address" to "10.0.0.1", "poolStart" to "10.0.0.100", "poolEnd" to "10.0.0.110"))
        create("cable1", "cat5-cable")
        connect("cable1", "printer1.eth0", "server1.eth0")
    }

    @Test
    fun `a valid topology applies as one atomic batch`() {
        val result = service.handle(validTopology().toCommand())

        assertTrue(result is CommandResult.Ok, "expected Ok, got $result")
        val objects = (service.handle(Command.ListObjects) as CommandResult.Ok).data as List<*>
        assertEquals(3, objects.size)
    }

    @Test
    fun `a failed batch leaves no objects and emits no events`() {
        val badTopology = topology {
            create("printer1", "printer")
            create("cable1", "cat5-cable")
            // References an endpoint that will never exist: rejects the whole batch.
            connect("cable1", "printer1.eth0", "nonexistent.eth0")
        }

        val result = service.handle(badTopology.toCommand())

        assertTrue(result is CommandResult.Rejected, "expected Rejected, got $result")
        assertEquals(ErrorCode.UNKNOWN_ENDPOINT, (result as CommandResult.Rejected).error.code)
        assertTrue((service.handle(Command.ListObjects) as CommandResult.Ok).data == emptyList<Any>(), "no partial objects")
        assertTrue(sink.events.isEmpty(), "no partial events")
    }

    @Test
    fun `duplicate ids within a batch are rejected`() {
        val batch = topology {
            create("printer1", "printer")
            create("printer1", "printer")
        }

        val result = service.handle(batch.toCommand())

        assertTrue(result is CommandResult.Rejected, "expected Rejected, got $result")
        assertEquals(ErrorCode.DUPLICATE_ID, (result as CommandResult.Rejected).error.code)
        assertTrue((service.handle(Command.ListObjects) as CommandResult.Ok).data == emptyList<Any>())
    }

    @Test
    fun `the DSL produces the same spec as a literal`() {
        val fromDsl = topology {
            create("printer1", "printer", mapOf("bootMs" to 500L))
            create("cable1", "cat5-cable")
            connect("cable1", "printer1.eth0", "server1.eth0")
        }
        val literal = TopologySpec(
            objects = listOf(
                TopologyObjectSpec("printer1", "printer", mapOf("bootMs" to 500L)),
                TopologyObjectSpec("cable1", "cat5-cable"),
            ),
            connections = listOf(TopologyConnectionSpec("cable1", "printer1.eth0", "server1.eth0")),
        )

        assertEquals(literal, fromDsl)
    }

    @Test
    fun `toCommand translates objects to Create and connections to Connect, in order`() {
        val spec = TopologySpec(
            objects = listOf(TopologyObjectSpec("printer1", "printer")),
            connections = listOf(TopologyConnectionSpec("cable1", "printer1.eth0", "server1.eth0")),
        )

        assertEquals(
            Command.ApplyTopology(
                listOf(
                    Command.Create("printer1", "printer"),
                    Command.Connect("cable1", EndpointRef("printer1.eth0"), EndpointRef("server1.eth0")),
                ),
            ),
            spec.toCommand(),
        )
    }
}
