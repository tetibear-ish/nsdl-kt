package com.a2z.nsdl.scenario

import com.a2z.nsdl.app.Command
import com.a2z.nsdl.nsdl.TopologyBuilder
import com.a2z.nsdl.nsdl.TopologySpec
import kotlin.time.Duration

/**
 * A step's parameter whose value is either known up front ([Literal]) or must be resolved from live
 * simulation state right before the step runs ([AddressOf], [MacOf]) -- e.g. the DHCP-assigned address
 * of a printer that doesn't exist until an earlier step runs it through acquisition.
 */
sealed interface ScenarioValue {
    data class Literal(val value: Any?) : ScenarioValue
    /** The current IPv4 address configured on the named endpoint (e.g. "printer1.eth0"). */
    data class AddressOf(val endpoint: String) : ScenarioValue
    /** The MAC address of the named endpoint. */
    data class MacOf(val endpoint: String) : ScenarioValue
}

/** One step of a [Scenario], executed in order. */
sealed interface ScenarioStep {
    /** Runs any [Command] supported outside a topology batch (power, connect, configure, ...). */
    data class Do(val command: Command) : ScenarioStep
    /** Sugar for [Do] with a [Command.Advance]; also what [Scenario.bound] is checked against. */
    data class Advance(val duration: Duration) : ScenarioStep
    /** Dispatches a scripted action (e.g. "submit a print job") via [Command.Invoke], resolving params first. */
    data class Invoke(val id: String, val action: String, val params: Map<String, ScenarioValue> = emptyMap()) : ScenarioStep
}

/**
 * A versioned, reproducible test case against the simulation: an initial topology, a scripted sequence
 * of actions, a virtual-time bound the run must stay within, and the assertions that determine pass/fail.
 * [version] lets a scenario document evolve (new step or assertion kinds) without breaking old ones --
 * the same role [com.a2z.nsdl.nsdl.TopologySource] plays for topologies, since nothing parses either
 * format from text yet.
 */
data class Scenario(
    val name: String,
    val version: Int = 1,
    val topology: TopologySpec,
    val steps: List<ScenarioStep>,
    /** The maximum total virtual time this scenario's Advance steps may consume; see [ScenarioRunner]. */
    val bound: Duration,
    val assertions: List<Assertion>,
)

/** A small programmatic builder for [Scenario], mirroring [com.a2z.nsdl.nsdl.topology]'s DSL. */
class ScenarioBuilder(private val name: String, private val bound: Duration) {
    var version: Int = 1
    private val topologyBuilder = TopologyBuilder()
    private val steps = mutableListOf<ScenarioStep>()
    private val assertions = mutableListOf<Assertion>()

    fun create(id: String, type: String, props: Map<String, Any?> = emptyMap()) = topologyBuilder.create(id, type, props)
    fun connect(cableId: String, a: String, b: String) = topologyBuilder.connect(cableId, a, b)

    fun powerOn(id: String) { steps += ScenarioStep.Do(Command.PowerOn(id)) }
    fun powerOff(id: String) { steps += ScenarioStep.Do(Command.PowerOff(id)) }
    fun configure(id: String, props: Map<String, Any?>) { steps += ScenarioStep.Do(Command.Configure(id, props)) }
    fun connectLater(cableId: String, a: String, b: String) {
        steps += ScenarioStep.Do(Command.Connect(cableId, com.a2z.nsdl.app.EndpointRef(a), com.a2z.nsdl.app.EndpointRef(b)))
    }
    fun advance(duration: Duration) { steps += ScenarioStep.Advance(duration) }
    fun invoke(id: String, action: String, params: Map<String, ScenarioValue> = emptyMap()) {
        steps += ScenarioStep.Invoke(id, action, params)
    }
    fun expect(assertion: Assertion) { assertions += assertion }

    fun build(): Scenario = Scenario(name, version, topologyBuilder.build(), steps.toList(), bound, assertions.toList())
}

fun scenario(name: String, bound: Duration, block: ScenarioBuilder.() -> Unit): Scenario =
    ScenarioBuilder(name, bound).apply(block).build()
