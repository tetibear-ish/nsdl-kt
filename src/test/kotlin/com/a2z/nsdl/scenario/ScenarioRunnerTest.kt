package com.a2z.nsdl.scenario

import com.a2z.nsdl.app.Command
import com.a2z.nsdl.app.CreationContext
import com.a2z.nsdl.app.InterfaceSpec
import com.a2z.nsdl.app.ObjectType
import com.a2z.nsdl.app.ObjectTypeSchema
import com.a2z.nsdl.app.SimObject
import com.a2z.nsdl.app.types.Cat5CableType
import com.a2z.nsdl.app.types.DhcpServerHostType
import com.a2z.nsdl.app.types.PrinterType
import com.a2z.nsdl.device.HostBuilder
import com.a2z.nsdl.events.EventHub
import com.a2z.nsdl.link.MediaType
import com.a2z.nsdl.model.ActionOutcome
import com.a2z.nsdl.model.Actionable
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.app.TypeRegistry
import com.a2z.nsdl.runtime.SimulationRuntime
import com.a2z.nsdl.sim.VirtualScheduler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A component with one scripted "echo" action, used to pin down Invoke/ScenarioValue resolution. */
private class EchoActionable(override val id: ObjectId) : com.a2z.nsdl.model.Inspectable, Actionable {
    var lastParams: Map<String, Any?> = emptyMap()
    override fun perform(action: String, params: Map<String, Any?>): ActionOutcome {
        lastParams = params
        return ActionOutcome(accepted = true, detail = action)
    }
    override fun snapshot() = com.a2z.nsdl.model.ObjectSnapshot(id, "echo", ObjectKind.PROTOCOL, emptyMap())
}

private object EchoHostType : ObjectType {
    lateinit var lastInstance: EchoActionable
    override val schema = ObjectTypeSchema(
        "echo-host", ObjectKind.DEVICE, properties = emptyList(),
        interfaces = listOf(InterfaceSpec("eth0", MediaType.TWISTED_PAIR)),
    )

    override fun create(id: ObjectId, props: Map<String, Any?>, ctx: CreationContext): SimObject {
        val builder = HostBuilder(id, schema.name, ctx.scheduler, ctx.events)
        builder.ethernet("eth0", ctx.nextMac())
        val device = builder.build { kotlin.time.Duration.ZERO }
        val echo = EchoActionable(id.child("echo"))
        lastInstance = echo
        return SimObject(root = device, components = device.interfaces + echo, power = device, endpoints = device.interfaces)
    }
}

class ScenarioRunnerTest {
    private val runtimes = mutableListOf<SimulationRuntime>()

    private fun newRuntime(seed: Long = 0L): SimulationRuntime {
        val registry = TypeRegistry().apply {
            register(PrinterType)
            register(DhcpServerHostType)
            register(Cat5CableType)
            register(EchoHostType)
        }
        val runtime = SimulationRuntime(VirtualScheduler(), EventHub(now = { 0L }), registry, randomSeed = seed)
        runtimes += runtime
        return runtime
    }

    @AfterEach
    fun tearDown() = runtimes.forEach { it.close() }

    private fun dhcpScenario(bound: kotlin.time.Duration, advanceMs: Long) = scenario("printer gets a lease", bound) {
        create("printer1", "printer")
        create("gateway1", "gateway", mapOf("address" to "10.0.0.1", "poolStart" to "10.0.0.100", "poolEnd" to "10.0.0.110"))
        create("cable1", "cat5-cable")
        connect("cable1", "printer1.eth0", "gateway1.eth0")
        powerOn("gateway1")
        powerOn("printer1")
        if (advanceMs > 0) advance(advanceMs.milliseconds)
        expect(AddressInPool("printer1.eth0", Ipv4Address.parse("10.0.0.100"), Ipv4Address.parse("10.0.0.110")))
        expect(LinkIsUp("printer1.eth0"))
    }

    @Test
    fun `every assertion passing reports Completed with all Pass outcomes`() {
        val result = ScenarioRunner(newRuntime()).run(dhcpScenario(bound = 5.seconds, advanceMs = 5000))

        val completed = result as? ScenarioResult.Completed ?: error("expected Completed, got $result")
        assertTrue(completed.passed, completed.outcomes.toString())
        assertEquals(2, completed.outcomes.size)
        assertTrue(completed.outcomes.all { it is AssertionOutcome.Pass })
        assertTrue(completed.snapshots.containsKey("printer1.eth0"))
    }

    @Test
    fun `an assertion that never becomes true fails with a message describing what is missing`() {
        // No Advance step at all: the printer (bootMs=3000 by default) never even finishes booting.
        val result = ScenarioRunner(newRuntime()).run(dhcpScenario(bound = ZERO, advanceMs = 0))

        val completed = result as? ScenarioResult.Completed ?: error("expected Completed, got $result")
        assertTrue(!completed.passed)
        val addressOutcome = completed.outcomes[0] as AssertionOutcome.Fail
        assertTrue(addressOutcome.message.contains("no IPv4 address"), addressOutcome.message)
        val linkOutcome = completed.outcomes[1] as AssertionOutcome.Fail
        assertTrue(linkOutcome.message.contains("never came up"), linkOutcome.message)
    }

    @Test
    fun `steps that would advance past the declared bound are rejected as malformed before anything runs`() {
        val result = ScenarioRunner(newRuntime()).run(dhcpScenario(bound = 1.seconds, advanceMs = 5000))

        val malformed = result as? ScenarioResult.Malformed ?: error("expected Malformed, got $result")
        assertTrue(malformed.reason.contains("exceeds"), malformed.reason)
    }

    @Test
    fun `a topology referencing an unknown type is rejected as malformed, not run`() {
        val badScenario = scenario("bad topology", bound = 1.seconds) {
            create("thing1", "toaster")
        }

        val result = ScenarioRunner(newRuntime()).run(badScenario)

        val malformed = result as? ScenarioResult.Malformed ?: error("expected Malformed, got $result")
        assertTrue(malformed.reason.contains("UNKNOWN_TYPE"), malformed.reason)
    }

    @Test
    fun `a step rejected mid-run stops the scenario and reports which step failed`() {
        val badStep = scenario("bad step", bound = 1.seconds) {
            create("printer1", "printer")
            powerOn("printer1")
            powerOn("does-not-exist")
            advance(1.seconds)
        }

        val result = ScenarioRunner(newRuntime()).run(badStep)

        val errored = result as? ScenarioResult.Errored ?: error("expected Errored, got $result")
        assertEquals(1, errored.stepIndex)
        assertEquals(com.a2z.nsdl.app.ErrorCode.UNKNOWN_OBJECT, errored.error.code)
    }

    @Test
    fun `an Invoke step with a literal param forwards it unchanged`() {
        val echoScenario = scenario("echo literal", bound = ZERO) {
            create("host1", "echo-host")
            invoke("host1.echo", "ping", mapOf("value" to ScenarioValue.Literal("hi")))
        }

        val result = ScenarioRunner(newRuntime()).run(echoScenario)

        assertTrue(result is ScenarioResult.Completed, "expected Completed, got $result")
        assertEquals("hi", EchoHostType.lastInstance.lastParams["value"])
    }

    @Test
    fun `an Invoke step resolves AddressOf and MacOf from live state right before the call`() {
        val echoScenario = scenario("echo refs", bound = 5.seconds) {
            create("printer1", "printer")
            create("gateway1", "gateway", mapOf("address" to "10.0.0.1", "poolStart" to "10.0.0.100", "poolEnd" to "10.0.0.110"))
            create("host1", "echo-host")
            create("cable1", "cat5-cable")
            create("cable2", "cat5-cable")
            connect("cable1", "printer1.eth0", "gateway1.eth0")
            powerOn("gateway1")
            powerOn("printer1")
            advance(5.seconds)
            invoke(
                "host1.echo", "describe",
                mapOf("address" to ScenarioValue.AddressOf("printer1.eth0"), "mac" to ScenarioValue.MacOf("printer1.eth0")),
            )
        }

        val result = ScenarioRunner(newRuntime()).run(echoScenario)

        val completed = result as? ScenarioResult.Completed ?: error("expected Completed, got $result")
        assertTrue(completed.events.isNotEmpty())
        val resolvedAddress = EchoHostType.lastInstance.lastParams["address"]
        assertTrue(resolvedAddress is Ipv4Address, "expected a resolved Ipv4Address, got $resolvedAddress")
    }

    @Test
    fun `an Invoke step whose ref cannot be resolved is reported as malformed`() {
        val echoScenario = scenario("unresolvable ref", bound = ZERO) {
            create("printer1", "printer")
            create("host1", "echo-host")
            invoke("host1.echo", "describe", mapOf("address" to ScenarioValue.AddressOf("printer1.eth0")))
        }

        val result = ScenarioRunner(newRuntime()).run(echoScenario)

        val malformed = result as? ScenarioResult.Malformed ?: error("expected Malformed, got $result")
        assertTrue(malformed.reason.contains("could not resolve"), malformed.reason)
    }

    @Test
    fun `replaying the same scenario against a fresh runtime with the same seed produces an identical trace`() {
        val first = ScenarioRunner(newRuntime(seed = 42L)).run(dhcpScenario(bound = 5.seconds, advanceMs = 5000)) as ScenarioResult.Completed
        val second = ScenarioRunner(newRuntime(seed = 42L)).run(dhcpScenario(bound = 5.seconds, advanceMs = 5000)) as ScenarioResult.Completed

        assertEquals(first.events, second.events)
        assertEquals(first.outcomes.map { it.message }, second.outcomes.map { it.message })
        assertTrue(first.passed && second.passed)
    }

    companion object {
        private val ZERO = kotlin.time.Duration.ZERO
    }
}
