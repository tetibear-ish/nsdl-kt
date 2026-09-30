package com.a2z.nsdl.app

import com.a2z.nsdl.app.types.Cat5CableType
import com.a2z.nsdl.app.types.DhcpServerHostType
import com.a2z.nsdl.app.types.PrinterType
import com.a2z.nsdl.device.HostBuilder
import com.a2z.nsdl.link.MediaType
import com.a2z.nsdl.model.EventPayload.BootCompleted
import com.a2z.nsdl.model.EventPayload.ConfigurationChanged
import com.a2z.nsdl.model.EventPayload.ObjectCreated
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A device with a differently-media'd interface, used only to make INCOMPATIBLE_MEDIA reachable. */
private object FiberHostType : ObjectType {
    override val schema = ObjectTypeSchema("fiber-host", ObjectKind.DEVICE, properties = emptyList(), interfaces = listOf(InterfaceSpec("eth0", MediaType.FIBER)))

    override fun create(id: ObjectId, props: Map<String, Any?>, ctx: CreationContext): SimObject {
        val builder = HostBuilder(id, schema.name, ctx.scheduler, ctx.events)
        val eth0 = builder.ethernet("eth0", ctx.nextMac(), media = MediaType.FIBER)
        val device = builder.build(bootDuration = { kotlin.time.Duration.ZERO })
        return SimObject(root = device, components = device.interfaces, power = device, endpoints = device.interfaces)
    }
}

class SimulationServiceTest {
    private val scheduler = VirtualScheduler()
    private val sink = RecordingSink()
    private val registry = TypeRegistry().apply {
        register(PrinterType)
        register(DhcpServerHostType)
        register(Cat5CableType)
        register(FiberHostType)
    }
    private lateinit var service: SimulationService

    @BeforeEach
    fun setUp() {
        service = SimulationService(scheduler, sink, registry)
    }

    private fun create(id: String, type: String, props: Map<String, Any?> = emptyMap()) =
        service.handle(Command.Create(id, type, props))

    private fun ok(result: CommandResult): CommandResult.Ok {
        assertTrue(result is CommandResult.Ok, "expected Ok, got $result")
        return result as CommandResult.Ok
    }

    private fun rejected(result: CommandResult): CommandError {
        assertTrue(result is CommandResult.Rejected, "expected Rejected, got $result")
        return (result as CommandResult.Rejected).error
    }

    // -- Create: ids and types --

    @Test
    fun `an invalid id is rejected as INVALID_ID`() {
        assertEquals(ErrorCode.INVALID_ID, rejected(create("bad id!", "printer")).code)
    }

    @Test
    fun `an unknown type is rejected as UNKNOWN_TYPE`() {
        assertEquals(ErrorCode.UNKNOWN_TYPE, rejected(create("p1", "toaster")).code)
    }

    @Test
    fun `a duplicate id is rejected as DUPLICATE_ID`() {
        ok(create("p1", "printer"))
        assertEquals(ErrorCode.DUPLICATE_ID, rejected(create("p1", "printer")).code)
    }

    @Test
    fun `a successful create emits ObjectCreated and returns the object's snapshot`() {
        val result = ok(create("p1", "printer"))
        assertEquals(listOf(ObjectCreated("printer", ObjectKind.DEVICE)), sink.of<ObjectCreated>(ObjectId("p1")))
        assertTrue(result.data is ObjectSnapshot)
        assertEquals(ObjectId("p1"), (result.data as ObjectSnapshot).id)
    }

    @Test
    fun `missing required properties are collected as MISSING_PROPERTY details`() {
        val error = rejected(create("s1", "dhcp-server-host"))
        assertEquals(ErrorCode.MISSING_PROPERTY, error.code)
        @Suppress("UNCHECKED_CAST")
        val errors = error.details["errors"] as List<Map<String, Any?>>
        assertEquals(setOf("address", "poolStart", "poolEnd"), errors.map { it["property"] }.toSet())
    }

    // -- Connect / Disconnect --

    private fun wirePrinterAndCable(): Pair<String, String> {
        ok(create("printer1", "printer"))
        ok(create("cable1", "cat5-cable"))
        ok(create("server1", "dhcp-server-host", mapOf("address" to "10.0.0.1", "poolStart" to "10.0.0.100", "poolEnd" to "10.0.0.110")))
        return "cable1" to "printer1.eth0"
    }

    @Test
    fun `connecting two valid endpoints succeeds`() {
        val (cable, printerEth0) = wirePrinterAndCable()
        val result = ok(service.handle(Command.Connect(cable, EndpointRef(printerEth0), EndpointRef("server1.eth0"))))
        assertTrue(result.changed)
    }

    @Test
    fun `an unknown endpoint is rejected as UNKNOWN_ENDPOINT`() {
        val (cable, printerEth0) = wirePrinterAndCable()
        val error = rejected(service.handle(Command.Connect(cable, EndpointRef(printerEth0), EndpointRef("nope.eth0"))))
        assertEquals(ErrorCode.UNKNOWN_ENDPOINT, error.code)
    }

    @Test
    fun `connecting to a non-cable object is rejected as NOT_A_CABLE`() {
        wirePrinterAndCable()
        val error = rejected(service.handle(Command.Connect("printer1", EndpointRef("printer1.eth0"), EndpointRef("server1.eth0"))))
        assertEquals(ErrorCode.NOT_A_CABLE, error.code)
    }

    @Test
    fun `repeating the same connect is a no-op with changed=false`() {
        val (cable, printerEth0) = wirePrinterAndCable()
        val conn = Command.Connect(cable, EndpointRef(printerEth0), EndpointRef("server1.eth0"))
        ok(service.handle(conn))
        sink.clear()

        val result = ok(service.handle(conn))
        assertFalse(result.changed)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `connecting a different pair to an already-connected cable is rejected as CABLE_OCCUPIED`() {
        val (cable, printerEth0) = wirePrinterAndCable()
        ok(service.handle(Command.Connect(cable, EndpointRef(printerEth0), EndpointRef("server1.eth0"))))

        ok(create("printer2", "printer", mapOf("mac" to "02:00:00:00:00:99")))
        val error = rejected(service.handle(Command.Connect(cable, EndpointRef("printer2.eth0"), EndpointRef("server1.eth0"))))
        assertEquals(ErrorCode.CABLE_OCCUPIED, error.code)
    }

    @Test
    fun `connecting an occupied endpoint to a different cable is rejected as ENDPOINT_OCCUPIED`() {
        val (cable, printerEth0) = wirePrinterAndCable()
        ok(service.handle(Command.Connect(cable, EndpointRef(printerEth0), EndpointRef("server1.eth0"))))

        ok(create("cable2", "cat5-cable"))
        ok(create("printer2", "printer", mapOf("mac" to "02:00:00:00:00:99")))
        val error = rejected(service.handle(Command.Connect("cable2", EndpointRef(printerEth0), EndpointRef("printer2.eth0"))))
        assertEquals(ErrorCode.ENDPOINT_OCCUPIED, error.code)
    }

    @Test
    fun `incompatible media between endpoints is rejected as INCOMPATIBLE_MEDIA`() {
        ok(create("printer1", "printer"))
        ok(create("fiber1", "fiber-host"))
        ok(create("cable1", "cat5-cable"))

        val error = rejected(service.handle(Command.Connect("cable1", EndpointRef("printer1.eth0"), EndpointRef("fiber1.eth0"))))
        assertEquals(ErrorCode.INCOMPATIBLE_MEDIA, error.code)
    }

    @Test
    fun `disconnecting an already-disconnected cable is a no-op with changed=false`() {
        ok(create("cable1", "cat5-cable"))
        val result = ok(service.handle(Command.Disconnect("cable1")))
        assertFalse(result.changed)
    }

    @Test
    fun `disconnecting a connected cable succeeds and a repeat is then a no-op`() {
        val (cable, printerEth0) = wirePrinterAndCable()
        ok(service.handle(Command.Connect(cable, EndpointRef(printerEth0), EndpointRef("server1.eth0"))))

        assertTrue(ok(service.handle(Command.Disconnect(cable))).changed)
        sink.clear()
        assertFalse(ok(service.handle(Command.Disconnect(cable))).changed)
        assertTrue(sink.events.isEmpty())
    }

    // -- Power --

    @Test
    fun `powering on an object is accepted immediately, booting is a separate later event`() {
        ok(create("printer1", "printer", mapOf("bootMs" to 3000L)))
        val result = ok(service.handle(Command.PowerOn("printer1")))
        assertTrue(result.changed)
        assertTrue(sink.of<BootCompleted>(ObjectId("printer1")).isEmpty(), "boot hasn't completed yet")

        scheduler.advanceBy(3.seconds)
        assertEquals(1, sink.of<BootCompleted>(ObjectId("printer1")).size)
    }

    @Test
    fun `powering on a cable is rejected as NOT_POWERABLE`() {
        ok(create("cable1", "cat5-cable"))
        assertEquals(ErrorCode.NOT_POWERABLE, rejected(service.handle(Command.PowerOn("cable1"))).code)
    }

    @Test
    fun `powering on an object that does not exist is rejected as UNKNOWN_OBJECT`() {
        assertEquals(ErrorCode.UNKNOWN_OBJECT, rejected(service.handle(Command.PowerOn("nope"))).code)
    }

    // -- Configure --

    @Test
    fun `configuring an immutable property is rejected as IMMUTABLE_PROPERTY`() {
        ok(create("printer1", "printer"))
        val error = rejected(service.handle(Command.Configure("printer1", mapOf("mac" to "02:00:00:00:00:09"))))
        assertEquals(ErrorCode.IMMUTABLE_PROPERTY, error.code)
    }

    @Test
    fun `configuring an unknown property is rejected as UNKNOWN_PROPERTY`() {
        ok(create("printer1", "printer"))
        val error = rejected(service.handle(Command.Configure("printer1", mapOf("color" to "beige"))))
        assertEquals(ErrorCode.UNKNOWN_PROPERTY, error.code)
    }

    @Test
    fun `a mutable property change emits ConfigurationChanged and applies at the next power-on`() {
        ok(create("printer1", "printer", mapOf("bootMs" to 3000L)))

        val result = ok(service.handle(Command.Configure("printer1", mapOf("bootMs" to 500L))))
        assertTrue(result.changed)
        assertEquals(listOf(ConfigurationChanged(mapOf("bootMs" to 500L))), sink.of<ConfigurationChanged>(ObjectId("printer1")))

        ok(service.handle(Command.PowerOn("printer1")))
        scheduler.advanceBy(500.milliseconds)
        assertEquals(1, sink.of<BootCompleted>(ObjectId("printer1")).size, "500ms boot, not the original 3000ms")
    }

    // -- Advance --

    @Test
    fun `advance reports truncated when the event budget runs out`() {
        // Two objects with different boot durations (1s, 3s) queue two distinct boot-completion
        // events; a budget of 1 must stop after the first one even though both are due by 10s.
        val limited = SimulationService(scheduler, sink, registry, Limits(maxEventsPerAdvance = 1))
        limited.handle(Command.Create("printer1", "printer"))
        limited.handle(Command.Create("server1", "dhcp-server-host", mapOf("address" to "10.0.0.1", "poolStart" to "10.0.0.100", "poolEnd" to "10.0.0.110")))
        ok(limited.handle(Command.PowerOn("printer1")))
        ok(limited.handle(Command.PowerOn("server1")))

        val advanceResult = ok(limited.handle(Command.Advance(10.seconds)))
        @Suppress("UNCHECKED_CAST")
        val data = advanceResult.data as Map<String, Any?>
        assertEquals(true, data["truncated"])
        assertEquals(1, data["eventsProcessed"])
    }

    @Test
    fun `advance requesting more than the configured limit is rejected as LIMIT_EXCEEDED`() {
        val limited = SimulationService(scheduler, sink, registry, Limits(maxAdvanceDuration = 5.seconds))
        val error = rejected(limited.handle(Command.Advance(10.seconds)))
        assertEquals(ErrorCode.LIMIT_EXCEEDED, error.code)
    }

    // -- ApplyTopology: shadow-validation edge cases beyond the happy/duplicate/failed-batch
    //    cases already covered at the nsdl-layer (TopologyTest), which exercises this through
    //    the DSL. These target the shadow state directly.

    @Test
    fun `a batch cannot connect an endpoint to itself`() {
        val batch = Command.ApplyTopology(
            listOf(
                Command.Create("printer1", "printer"),
                Command.Create("cable1", "cat5-cable"),
                Command.Connect("cable1", EndpointRef("printer1.eth0"), EndpointRef("printer1.eth0")),
            ),
        )

        val error = rejected(service.handle(batch))
        assertEquals(ErrorCode.SELF_CONNECTION, error.code)
        assertTrue((service.handle(Command.ListObjects) as CommandResult.Ok).data == emptyList<Any>())
    }

    @Test
    fun `a batch can connect to an endpoint that already exists outside the batch`() {
        ok(create("server1", "dhcp-server-host", mapOf("address" to "10.0.0.1", "poolStart" to "10.0.0.100", "poolEnd" to "10.0.0.110")))

        val batch = Command.ApplyTopology(
            listOf(
                Command.Create("printer1", "printer"),
                Command.Create("cable1", "cat5-cable"),
                Command.Connect("cable1", EndpointRef("printer1.eth0"), EndpointRef("server1.eth0")),
            ),
        )
        val result = ok(service.handle(batch))
        assertTrue(result.changed)
    }

    // -- ListTypes / ListObjects / Inspect --

    @Test
    fun `ListTypes returns every registered schema`() {
        val result = ok(service.handle(Command.ListTypes))
        @Suppress("UNCHECKED_CAST")
        val schemas = result.data as List<ObjectTypeSchema>
        assertEquals(setOf("printer", "dhcp-server-host", "cat5-cable", "fiber-host"), schemas.map { it.name }.toSet())
    }

    @Test
    fun `ListObjects returns a snapshot per created object`() {
        ok(create("printer1", "printer"))
        ok(create("cable1", "cat5-cable"))

        val result = ok(service.handle(Command.ListObjects))
        @Suppress("UNCHECKED_CAST")
        val snapshots = result.data as List<ObjectSnapshot>
        assertEquals(setOf(ObjectId("printer1"), ObjectId("cable1")), snapshots.map { it.id }.toSet())
    }

    @Test
    fun `inspecting an unknown object is rejected as UNKNOWN_OBJECT`() {
        assertEquals(ErrorCode.UNKNOWN_OBJECT, rejected(service.handle(Command.Inspect("nope"))).code)
    }

    @Test
    fun `inspecting a known object returns its snapshot`() {
        ok(create("printer1", "printer"))
        val result = ok(service.handle(Command.Inspect("printer1")))
        assertEquals(ObjectId("printer1"), (result.data as ObjectSnapshot).id)
    }
}
