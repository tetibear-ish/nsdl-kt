package com.a2z.nsdl.app

import com.a2z.nsdl.app.types.Cat5CableType
import com.a2z.nsdl.app.types.DhcpServerHostType
import com.a2z.nsdl.app.types.EthernetSwitchType
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

    private fun snapshot(id: String): ObjectSnapshot =
        ok(service.handle(Command.Inspect(id))).data as ObjectSnapshot

    @Suppress("UNCHECKED_CAST")
    private fun graphAnalysis(id: String): Map<String, Any?> =
        ok(service.handle(Command.AnalyzeGraph(id))).data as Map<String, Any?>

    private fun connect(cable: String, a: String, b: String) {
        ok(create(cable, "cat5-cable"))
        ok(service.handle(Command.Connect(cable, EndpointRef(a), EndpointRef(b))))
    }

    @Test
    fun `graph analysis reports component degree hop counts and articulation status for a node`() {
        registry.register(EthernetSwitchType)
        ok(create("printer1", "printer"))
        ok(create("printer2", "printer"))
        ok(create("switch1", "ethernet-switch"))
        ok(create("gateway1", "gateway"))
        connect("cable1", "printer1.eth0", "switch1.port1")
        connect("cable2", "printer2.eth0", "switch1.port2")
        connect("cable3", "gateway1.eth0", "switch1.port3")

        assertEquals(
            mapOf(
                "nodeId" to "switch1",
                "connectedComponent" to listOf("gateway1", "printer1", "printer2", "switch1"),
                "degree" to 3,
                "shortestPaths" to mapOf("gateway1" to 1, "printer1" to 1, "printer2" to 1, "switch1" to 0),
                "articulationPoint" to true,
            ),
            graphAnalysis("switch1"),
        )

        val printerAnalysis = graphAnalysis("printer1")
        assertEquals(mapOf("gateway1" to 2, "printer1" to 0, "printer2" to 2, "switch1" to 1), printerAnalysis["shortestPaths"])
        assertEquals(false, printerAnalysis["articulationPoint"])
        assertEquals(false, ok(service.handle(Command.AnalyzeGraph("switch1"))).changed)
    }

    @Test
    fun `automatically assigned mac addresses are random local unicast addresses`() {
        ok(create("printer1", "printer"))
        ok(create("printer2", "printer"))

        val first = (snapshot("printer1.eth0").state["mac"] as String)
        val second = (snapshot("printer2.eth0").state["mac"] as String)

        assertTrue(first != second)
        assertTrue(first.startsWith("02:") || first.startsWith("06:") || first.startsWith("0a:") || first.startsWith("0e:"))
        assertTrue(second.startsWith("02:") || second.startsWith("06:") || second.startsWith("0a:") || second.startsWith("0e:"))
    }

    @Test
    fun `graph analysis treats a disconnected device as an isolated component`() {
        ok(create("printer1", "printer"))

        assertEquals(
            mapOf(
                "nodeId" to "printer1",
                "connectedComponent" to listOf("printer1"),
                "degree" to 0,
                "shortestPaths" to mapOf("printer1" to 0),
                "articulationPoint" to false,
            ),
            graphAnalysis("printer1"),
        )
    }

    @Test
    fun `graph analysis rejects unknown objects and cable ids`() {
        assertEquals(ErrorCode.UNKNOWN_OBJECT, rejected(service.handle(Command.AnalyzeGraph("missing"))).code)
        ok(create("cable1", "cat5-cable"))
        assertEquals(ErrorCode.INVALID_REQUEST, rejected(service.handle(Command.AnalyzeGraph("cable1"))).code)
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
    fun `gateway can be created with its default network and pool`() {
        val result = ok(create("s1", "gateway"))
        assertTrue(result.data is ObjectSnapshot)
    }

    // -- Connect / Disconnect --

    private fun wirePrinterAndCable(): Pair<String, String> {
        ok(create("printer1", "printer"))
        ok(create("cable1", "cat5-cable"))
        ok(create("server1", "gateway", mapOf("address" to "10.0.0.1", "poolStart" to "10.0.0.100", "poolEnd" to "10.0.0.110")))
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
    fun `configuration validation is atomic and leaves the running object untouched`() {
        ok(create("printer1", "printer"))
        ok(service.handle(Command.PowerOn("printer1")))
        scheduler.advanceBy(3.seconds)

        val error = rejected(service.handle(Command.Configure("printer1", mapOf("bootMs" to "bad", "mac" to "02:00:00:00:00:09"))))
        assertEquals(ErrorCode.INVALID_PROPERTY, error.code)
        assertEquals("ON", snapshot("printer1").state["power"])
        assertEquals(3000L, snapshot("printer1").state["bootMs"])
    }

    @Test
    fun `configuring an unknown property is rejected as UNKNOWN_PROPERTY`() {
        ok(create("printer1", "printer"))
        val error = rejected(service.handle(Command.Configure("printer1", mapOf("color" to "beige"))))
        assertEquals(ErrorCode.UNKNOWN_PROPERTY, error.code)
    }

    @Test
    fun `configuring a powered device replaces it and begins a fresh boot`() {
        ok(create("printer1", "printer", mapOf("bootMs" to 3000L)))
        ok(service.handle(Command.PowerOn("printer1")))
        scheduler.advanceBy(3.seconds)

        val result = ok(service.handle(Command.Configure("printer1", mapOf("bootMs" to 500L))))
        assertTrue(result.changed)
        assertEquals(listOf(ConfigurationChanged(mapOf("bootMs" to 500L))), sink.of<ConfigurationChanged>(ObjectId("printer1")))
        assertEquals("BOOTING", snapshot("printer1").state["power"])

        scheduler.advanceBy(500.milliseconds)
        assertEquals("ON", snapshot("printer1").state["power"])
        assertEquals(2, sink.of<BootCompleted>(ObjectId("printer1")).size, "replacement boots in 500ms")
    }

    @Test
    fun `configuring an off device preserves its off state`() {
        ok(create("printer1", "printer"))

        ok(service.handle(Command.Configure("printer1", mapOf("bootMs" to 25L))))

        assertEquals("OFF", snapshot("printer1").state["power"])
        scheduler.advanceBy(1.seconds)
        assertTrue(sink.of<BootCompleted>(ObjectId("printer1")).isEmpty())
    }

    @Test
    fun `reconfiguring while booting cancels the old generation timer`() {
        ok(create("printer1", "printer", mapOf("bootMs" to 1000L)))
        ok(service.handle(Command.PowerOn("printer1")))
        scheduler.advanceBy(500.milliseconds)

        ok(service.handle(Command.Configure("printer1", mapOf("bootMs" to 2000L))))
        scheduler.advanceBy(500.milliseconds)
        assertEquals("BOOTING", snapshot("printer1").state["power"], "old boot timer cannot complete the replacement")

        scheduler.advanceBy(1500.milliseconds)
        assertEquals("ON", snapshot("printer1").state["power"])
    }

    @Test
    fun `reconfiguration preserves compatible cables while clearing DHCP and switch volatile state`() {
        registry.register(EthernetSwitchType)
        ok(create("printer1", "printer", mapOf("bootMs" to 10L)))
        ok(create("switch1", "ethernet-switch"))
        ok(create("gateway1", "gateway"))
        connect("cable1", "printer1.eth0", "switch1.port1")
        connect("cable2", "gateway1.eth0", "switch1.port2")
        listOf("printer1", "switch1", "gateway1").forEach { ok(service.handle(Command.PowerOn(it))) }
        scheduler.advanceBy(5.seconds)
        assertTrue((snapshot("printer1.eth0").state["ipv4"] as Map<*, *>)["address"] != null)
        assertTrue((snapshot("switch1").state["learnedAddresses"] as List<*>).isNotEmpty())

        ok(service.handle(Command.Configure("printer1", mapOf("bootMs" to 20L))))
        ok(service.handle(Command.Configure("switch1", mapOf("bootMs" to 10L))))

        assertEquals(null, snapshot("printer1.eth0").state["ipv4"])
        assertEquals(emptyList<Any>(), snapshot("switch1").state["learnedAddresses"])
        assertEquals("BOOTING", snapshot("printer1").state["power"])
        assertEquals("BOOTING", snapshot("switch1").state["power"])
        assertEquals(listOf(ObjectId("printer1.eth0"), ObjectId("switch1.port1")), snapshot("cable1").relations["endpoints"])
        assertEquals(listOf(ObjectId("gateway1.eth0"), ObjectId("switch1.port2")), snapshot("cable2").relations["endpoints"])
    }

    // -- Advance --

    @Test
    fun `advance reports truncated when the event budget runs out`() {
        // Two objects with different boot durations (1s, 3s) queue two distinct boot-completion
        // events; a budget of 1 must stop after the first one even though both are due by 10s.
        val limited = SimulationService(scheduler, sink, registry, Limits(maxEventsPerAdvance = 1))
        limited.handle(Command.Create("printer1", "printer"))
        limited.handle(Command.Create("server1", "gateway", mapOf("address" to "10.0.0.1", "poolStart" to "10.0.0.100", "poolEnd" to "10.0.0.110")))
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
        ok(create("server1", "gateway", mapOf("address" to "10.0.0.1", "poolStart" to "10.0.0.100", "poolEnd" to "10.0.0.110")))

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
        assertEquals(setOf("printer", "gateway", "cat5-cable", "fiber-host"), schemas.map { it.name }.toSet())
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

    @Test
    fun `inspecting a sub-component by its own id returns that component's snapshot, not the root's`() {
        ok(create("printer1", "printer"))
        val result = ok(service.handle(Command.Inspect("printer1.dhcp-client")))
        assertEquals(ObjectId("printer1.dhcp-client"), (result.data as ObjectSnapshot).id)
    }

    // -- Delete --

    @Test
    fun `deleting an unknown object is rejected as UNKNOWN_OBJECT`() {
        assertEquals(ErrorCode.UNKNOWN_OBJECT, rejected(service.handle(Command.Delete("nope"))).code)
    }

    @Test
    fun `deleting with an invalid id is rejected as INVALID_ID`() {
        assertEquals(ErrorCode.INVALID_ID, rejected(service.handle(Command.Delete("bad id!"))).code)
    }

    @Test
    fun `deleting a standalone object removes it, so a later inspect is UNKNOWN_OBJECT`() {
        ok(create("printer1", "printer"))

        val result = ok(service.handle(Command.Delete("printer1")))
        assertTrue(result.changed)

        assertEquals(ErrorCode.UNKNOWN_OBJECT, rejected(service.handle(Command.Inspect("printer1"))).code)
    }

    @Test
    fun `deleting the same object twice -- the second delete is UNKNOWN_OBJECT, not a silent no-op`() {
        ok(create("printer1", "printer"))
        ok(service.handle(Command.Delete("printer1")))

        assertEquals(ErrorCode.UNKNOWN_OBJECT, rejected(service.handle(Command.Delete("printer1"))).code)
    }

    @Test
    fun `a deleted id can be reused by a later create`() {
        ok(create("printer1", "printer"))
        ok(service.handle(Command.Delete("printer1")))

        val result = ok(create("printer1", "printer"))
        assertEquals(ObjectId("printer1"), (result.data as ObjectSnapshot).id)
    }

    @Test
    fun `deleting a connected cable disconnects it first`() {
        val (cable, printerEth0) = wirePrinterAndCable()
        ok(service.handle(Command.Connect(cable, EndpointRef(printerEth0), EndpointRef("server1.eth0"))))

        ok(service.handle(Command.Delete(cable)))

        assertEquals(ErrorCode.UNKNOWN_OBJECT, rejected(service.handle(Command.Disconnect(cable))).code)
    }

    @Test
    fun `deleting a device with no cables attached removes only that device`() {
        ok(create("printer1", "printer"))
        ok(create("cable1", "cat5-cable"))

        val result = ok(service.handle(Command.Delete("printer1")))
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("printer1"), (result.data as Map<String, Any?>)["deleted"])

        ok(service.handle(Command.Inspect("cable1")))
    }

    @Test
    fun `deleting a device atomically deletes its attached cable too`() {
        val (cable, printerEth0) = wirePrinterAndCable()
        ok(service.handle(Command.Connect(cable, EndpointRef(printerEth0), EndpointRef("server1.eth0"))))

        val result = ok(service.handle(Command.Delete("printer1")))
        @Suppress("UNCHECKED_CAST")
        assertEquals(setOf("printer1", cable), (result.data as Map<String, Any?>)["deleted"].let { (it as List<*>).toSet() })

        assertEquals(ErrorCode.UNKNOWN_OBJECT, rejected(service.handle(Command.Inspect(cable))).code, "the cable itself is gone, not just disconnected")
    }

    @Test
    fun `deleting a multi-port device removes every cable attached to any of its ports, leaving the peers intact`() {
        registry.register(EthernetSwitchType)
        ok(create("switch1", "ethernet-switch"))
        ok(create("printer1", "printer"))
        ok(create("printer2", "printer", mapOf("mac" to "02:00:00:00:00:99")))
        ok(create("cable1", "cat5-cable"))
        ok(create("cable2", "cat5-cable"))
        ok(service.handle(Command.Connect("cable1", EndpointRef("switch1.port1"), EndpointRef("printer1.eth0"))))
        ok(service.handle(Command.Connect("cable2", EndpointRef("switch1.port2"), EndpointRef("printer2.eth0"))))

        val result = ok(service.handle(Command.Delete("switch1")))
        @Suppress("UNCHECKED_CAST")
        assertEquals(setOf("switch1", "cable1", "cable2"), ((result.data as Map<String, Any?>)["deleted"] as List<*>).toSet())

        assertEquals(ErrorCode.UNKNOWN_OBJECT, rejected(service.handle(Command.Inspect("cable1"))).code)
        assertEquals(ErrorCode.UNKNOWN_OBJECT, rejected(service.handle(Command.Inspect("cable2"))).code)
        ok(service.handle(Command.Inspect("printer1")))
        ok(service.handle(Command.Inspect("printer2")))
    }

    @Test
    fun `deleting a powered-on device powers it off first, so its lifecycle cleans up before removal`() {
        ok(create("printer1", "printer", mapOf("bootMs" to 3000L)))
        ok(service.handle(Command.PowerOn("printer1")))

        ok(service.handle(Command.Delete("printer1")))

        assertEquals(ErrorCode.UNKNOWN_OBJECT, rejected(service.handle(Command.PowerOff("printer1"))).code)
    }
}
