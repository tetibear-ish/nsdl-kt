package com.a2z.nsdl.app

import com.a2z.nsdl.app.types.Cat5CableType
import com.a2z.nsdl.app.types.RoutedGatewayType
import com.a2z.nsdl.model.EventPayload.ConfigurationChanged
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Confirms the multi-interface routed-gateway rebuilds cleanly through SimulationService's existing
 * Configure mechanism (handleConfigure in SimulationService.kt): the whole object is recreated via
 * ObjectType.create and cables are unplugged and replugged by endpoint id. Since RoutedGatewayType
 * always creates deterministic child ids ("<id>.lan", "<id>.wan") regardless of property values,
 * cables attached to BOTH interfaces survive a reconfiguration unchanged -- nothing in
 * SimulationService needed to change for a device with more than one endpoint.
 */
class RoutedGatewayConfigureTest {
    private val scheduler = VirtualScheduler()
    private val sink = RecordingSink()
    private val registry = TypeRegistry().apply {
        register(RoutedGatewayType)
        register(Cat5CableType)
    }
    private lateinit var service: SimulationService

    @BeforeEach
    fun setUp() {
        service = SimulationService(scheduler, sink, registry)
    }

    private fun ok(result: CommandResult): CommandResult.Ok {
        assertTrue(result is CommandResult.Ok, "expected Ok, got $result")
        return result as CommandResult.Ok
    }

    @Test
    fun `reconfiguring the lan address keeps both the lan and wan cables connected`() {
        ok(service.handle(Command.Create("gw1", "routed-gateway", mapOf("wanAddress" to "203.0.113.1"))))
        ok(service.handle(Command.Create("peerA", "routed-gateway", mapOf("wanAddress" to "203.0.113.2"))))
        ok(service.handle(Command.Create("lanCable", "cat5-cable")))
        ok(service.handle(Command.Create("wanCable", "cat5-cable")))
        ok(service.handle(Command.Connect("lanCable", EndpointRef("gw1.lan"), EndpointRef("peerA.lan"))))
        ok(service.handle(Command.Connect("wanCable", EndpointRef("gw1.wan"), EndpointRef("peerA.wan"))))

        val result = ok(service.handle(Command.Configure("gw1", mapOf("lanAddress" to "10.5.5.1"))))
        assertTrue(result.changed)
        assertTrue(sink.of<ConfigurationChanged>().isNotEmpty())

        // Both endpoints must still resolve and still be connected after the rebuild: a repeated
        // connect to the SAME pair is a no-op (changed=false), which is only possible if the cable
        // is still attached exactly there.
        val relan = ok(service.handle(Command.Connect("lanCable", EndpointRef("gw1.lan"), EndpointRef("peerA.lan"))))
        assertTrue(!relan.changed, "lan cable should still be connected to the same pair")
        val rewan = ok(service.handle(Command.Connect("wanCable", EndpointRef("gw1.wan"), EndpointRef("peerA.wan"))))
        assertTrue(!rewan.changed, "wan cable should still be connected to the same pair")
    }
}
