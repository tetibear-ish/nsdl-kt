package com.a2z.nsdl.runtime

import com.a2z.nsdl.app.Command
import com.a2z.nsdl.app.CommandResult
import com.a2z.nsdl.app.CreationContext
import com.a2z.nsdl.app.ErrorCode
import com.a2z.nsdl.app.ObjectType
import com.a2z.nsdl.app.ObjectTypeSchema
import com.a2z.nsdl.app.SimObject
import com.a2z.nsdl.app.TypeRegistry
import com.a2z.nsdl.app.types.Cat5CableType
import com.a2z.nsdl.app.types.DhcpServerHostType
import com.a2z.nsdl.app.types.PrinterType
import com.a2z.nsdl.events.Delivery
import com.a2z.nsdl.events.EventFilter
import com.a2z.nsdl.events.EventHub
import com.a2z.nsdl.events.SubscribeResult
import com.a2z.nsdl.model.Inspectable
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.sim.VirtualScheduler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** Captures which thread ran create(), to verify runtime confinement without touching EventHub's API. */
private object ThreadCapturingType : ObjectType {
    var capturedThread: Thread? = null
    override val schema = ObjectTypeSchema("probe", ObjectKind.DEVICE, properties = emptyList())
    override fun create(id: ObjectId, props: Map<String, Any?>, ctx: CreationContext): SimObject {
        capturedThread = Thread.currentThread()
        val root = object : Inspectable {
            override val id = id
            override fun snapshot() = ObjectSnapshot(id, "probe", ObjectKind.DEVICE, emptyMap())
        }
        return SimObject(root = root)
    }
}

class SimulationRuntimeTest {
    private val runtimes = mutableListOf<SimulationRuntime>()

    private fun newRuntime(seed: Long = 0L): SimulationRuntime {
        val registry = TypeRegistry().apply {
            register(PrinterType)
            register(DhcpServerHostType)
            register(Cat5CableType)
            register(ThreadCapturingType)
        }
        val runtime = SimulationRuntime(VirtualScheduler(), EventHub(now = { 0L }), registry, randomSeed = seed)
        runtimes += runtime
        return runtime
    }

    @AfterEach
    fun tearDown() {
        runtimes.forEach { it.close() }
    }

    @Test
    fun `commands run on the runtime's own dedicated daemon thread, not the caller's`() {
        val runtime = newRuntime()
        runtime.submit(Request(Command.Create("probe1", "probe")))

        val captured = ThreadCapturingType.capturedThread
        assertNotNull(captured)
        assertNotEquals(Thread.currentThread(), captured)
        assertEquals(SimulationRuntime.THREAD_NAME, captured!!.name)
        assertTrue(captured.isDaemon)
    }

    @Test
    fun `a repeated request id with the same command returns the cached result without re-executing`() {
        val runtime = newRuntime()
        val request = Request(Command.Create("printer1", "printer"), requestId = "req-1")

        val first = runtime.submit(request)
        val second = runtime.submit(request)

        assertEquals(first, second)
        assertEquals(1, runtime.journalSnapshot().size, "the retry did not add a second journal entry")
    }

    @Test
    fun `a repeated request id with a different command is rejected as IDEMPOTENCY_CONFLICT`() {
        val runtime = newRuntime()
        runtime.submit(Request(Command.Create("printer1", "printer"), requestId = "req-1"))

        val conflict = runtime.submit(Request(Command.Create("printer2", "printer"), requestId = "req-1"))

        assertTrue(conflict.result is CommandResult.Rejected)
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, (conflict.result as CommandResult.Rejected).error.code)
    }

    @Test
    fun `the journal records accepted mutating commands in accepted order, and skips rejections and reads`() {
        val runtime = newRuntime()
        runtime.submit(Request(Command.Create("printer1", "printer")))
        runtime.submit(Request(Command.Create("printer1", "printer"))) // rejected: DUPLICATE_ID
        runtime.submit(Request(Command.ListObjects)) // a read, not mutating
        runtime.submit(Request(Command.PowerOn("printer1")))
        runtime.submit(Request(Command.Advance(1.seconds)))

        assertEquals(
            listOf(
                Command.Create("printer1", "printer"),
                Command.PowerOn("printer1"),
                Command.Advance(1.seconds),
            ),
            runtime.journalSnapshot(),
        )
    }

    @Test
    fun `an unexpected exception becomes an INTERNAL result and the runtime keeps working`() {
        val throwingType = object : ObjectType {
            override val schema = ObjectTypeSchema("boom", ObjectKind.DEVICE, properties = emptyList())
            override fun create(id: ObjectId, props: Map<String, Any?>, ctx: CreationContext): SimObject =
                throw IllegalStateException("boom")
        }
        val registry = TypeRegistry().apply { register(throwingType) }
        val runtime = SimulationRuntime(VirtualScheduler(), EventHub(now = { 0L }), registry).also { runtimes += it }

        val result = runtime.submit(Request(Command.Create("x1", "boom")))
        assertTrue(result.result is CommandResult.Rejected)
        assertEquals(ErrorCode.INTERNAL, (result.result as CommandResult.Rejected).error.code)

        // The thread survived: a later, unrelated command still works.
        val followUp = runtime.submit(Request(Command.ListTypes))
        assertTrue(followUp.result is CommandResult.Ok)
    }

    @Test
    fun `subscribing from a submit result's revision replays everything after it, with no gap`() {
        val runtime = newRuntime()
        val created = runtime.submit(Request(Command.Create("printer1", "printer")))
        assertTrue(created.result is CommandResult.Ok)

        val sub = (runtime.subscribe(EventFilter(), from = created.revision, capacity = 100) as SubscribeResult.Subscribed).subscription
        runtime.submit(Request(Command.PowerOn("printer1")))

        val types = generateSequence { sub.poll() }.filterIsInstance<Delivery.Event>().map { it.record.type }.toList()
        assertEquals(listOf("PowerOnStarted"), types, "the ObjectCreated that produced the revision is not replayed again")
    }

    private fun runFullAcquisition(runtime: SimulationRuntime) {
        runtime.submit(Request(Command.Create("printer1", "printer", mapOf("bootMs" to 500L))))
        runtime.submit(
            Request(
                Command.Create(
                    "server1", "dhcp-server-host",
                    mapOf("address" to "10.0.0.1", "poolStart" to "10.0.0.100", "poolEnd" to "10.0.0.110"),
                ),
            ),
        )
        runtime.submit(Request(Command.Create("cable1", "cat5-cable")))
        runtime.submit(
            Request(
                Command.Connect(
                    "cable1",
                    com.a2z.nsdl.app.EndpointRef("printer1.eth0"),
                    com.a2z.nsdl.app.EndpointRef("server1.eth0"),
                ),
            ),
        )
        runtime.submit(Request(Command.PowerOn("server1")))
        runtime.submit(Request(Command.PowerOn("printer1")))
        runtime.submit(Request(Command.Advance(5.seconds)))
    }

    private fun eventTrace(runtime: SimulationRuntime): List<Triple<ObjectId, String, Any?>> {
        val sub = (runtime.subscribe(EventFilter(), from = 0, capacity = 10_000) as SubscribeResult.Subscribed).subscription
        return generateSequence { sub.poll() }.filterIsInstance<Delivery.Event>()
            .map { Triple(it.record.source, it.record.type, it.record.payload) }.toList()
    }

    @Test
    fun `replaying the same journal with the same seed produces an identical event trace`() {
        val first = newRuntime(seed = 42L)
        runFullAcquisition(first)
        val firstTrace = eventTrace(first)
        val journal = first.journalSnapshot()

        val replay = newRuntime(seed = 42L)
        journal.forEach { replay.submit(Request(it)) }

        assertEquals(firstTrace, eventTrace(replay))
    }

    @Test
    fun `a different seed produces a different DHCP xid`() {
        val a = newRuntime(seed = 1L)
        runFullAcquisition(a)
        val xidA = (a.submit(Request(Command.Inspect("printer1.dhcp-client"))).result as CommandResult.Ok)
            .let { (it.data as ObjectSnapshot).state["xid"] }

        val b = newRuntime(seed = 2L)
        runFullAcquisition(b)
        val xidB = (b.submit(Request(Command.Inspect("printer1.dhcp-client"))).result as CommandResult.Ok)
            .let { (it.data as ObjectSnapshot).state["xid"] }

        assertNotEquals(xidA, xidB)
    }

    @Test
    fun `currentRevision matches the last submit's own revision`() {
        val runtime = newRuntime()
        val created = runtime.submit(Request(Command.Create("printer1", "printer")))
        assertEquals(created.revision, runtime.currentRevision())
    }
}
