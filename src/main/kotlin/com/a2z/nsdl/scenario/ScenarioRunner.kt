package com.a2z.nsdl.scenario

import com.a2z.nsdl.app.Command
import com.a2z.nsdl.app.CommandError
import com.a2z.nsdl.app.CommandResult
import com.a2z.nsdl.events.Delivery
import com.a2z.nsdl.events.EventFilter
import com.a2z.nsdl.events.EventRecord
import com.a2z.nsdl.events.SubscribeResult
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.net.ArpOperation
import com.a2z.nsdl.net.ArpPacket
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.net.UdpDatagram
import com.a2z.nsdl.nsdl.toCommand
import com.a2z.nsdl.runtime.Request
import com.a2z.nsdl.runtime.SimulationRuntime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/** One assertion's verdict: [message] is self-contained enough to show a student without the scenario source. */
sealed interface AssertionOutcome {
    val assertion: Assertion
    val message: String
    val evidence: List<EventRecord>

    data class Pass(override val assertion: Assertion, override val message: String, override val evidence: List<EventRecord> = emptyList()) : AssertionOutcome
    data class Fail(override val assertion: Assertion, override val message: String, override val evidence: List<EventRecord> = emptyList()) : AssertionOutcome
}

sealed interface ScenarioResult {
    val name: String

    /** The scenario itself is structurally invalid (bad bound, rejected topology, unresolvable step); nothing ran. */
    data class Malformed(override val name: String, val reason: String) : ScenarioResult

    /** Step [stepIndex] (0-based, after the topology) was rejected while running; later steps did not run. */
    data class Errored(override val name: String, val stepIndex: Int, val error: CommandError) : ScenarioResult

    /** Every step ran to completion; [outcomes] holds one verdict per declared assertion, in order. */
    data class Completed(
        override val name: String,
        val outcomes: List<AssertionOutcome>,
        val events: List<EventRecord>,
        val snapshots: Map<String, ObjectSnapshot>,
    ) : ScenarioResult {
        val passed: Boolean get() = outcomes.all { it is AssertionOutcome.Pass }
    }
}

/**
 * Runs one [Scenario] end to end against a fresh [SimulationRuntime]: applies the topology atomically,
 * executes each step in order, then evaluates every assertion against the complete accumulated event
 * trace and final object snapshots -- not one sampled instant, so it doesn't matter how coarsely a
 * scenario's Advance steps are chunked. Nothing here depends on how the runtime was constructed or who
 * is asking, so the same engine drives a CLI subcommand, an HTTP endpoint and a JUnit test.
 *
 * One runner is meant to run its runtime's one and only scenario: the topology is created from empty
 * state, so a second run on the same runtime would fail every Create with DUPLICATE_ID.
 */
class ScenarioRunner(private val runtime: SimulationRuntime) {
    fun run(scenario: Scenario): ScenarioResult {
        val totalAdvance = scenario.steps.fold(ZERO) { total, step -> total + step.advanceCost() }
        if (totalAdvance > scenario.bound) {
            return ScenarioResult.Malformed(
                scenario.name,
                "steps advance a total of $totalAdvance, which exceeds the declared bound of ${scenario.bound}",
            )
        }

        val subscription = when (val subscribed = runtime.subscribe(EventFilter(), from = 0, capacity = EVENT_CAPACITY)) {
            is SubscribeResult.Subscribed -> subscribed.subscription
            SubscribeResult.CursorExpired -> return ScenarioResult.Malformed(scenario.name, "could not observe events from the start of a fresh runtime")
        }
        val events = mutableListOf<EventRecord>()
        var overflowed = false
        fun drain() {
            while (true) {
                when (val delivery = subscription.poll()) {
                    is Delivery.Event -> events += delivery.record
                    is Delivery.Gap -> { overflowed = true; return }
                    null -> return
                }
            }
        }

        val topologyOutcome = runtime.submit(Request(scenario.topology.toCommand()))
        drain()
        (topologyOutcome.result as? CommandResult.Rejected)?.let {
            return ScenarioResult.Malformed(scenario.name, "initial topology was rejected: ${it.error.code} ${it.error.message}")
        }

        for ((index, step) in scenario.steps.withIndex()) {
            val command = when (step) {
                is ScenarioStep.Do -> step.command
                is ScenarioStep.Advance -> Command.Advance(step.duration)
                is ScenarioStep.Invoke -> {
                    val resolvedParams = mutableMapOf<String, Any?>()
                    for ((key, value) in step.params) {
                        if (value is ScenarioValue.Literal) {
                            resolvedParams[key] = value.value
                            continue
                        }
                        val resolved = resolve(value)
                            ?: return ScenarioResult.Malformed(
                                scenario.name,
                                "step $index ('${step.id}.${step.action}'): could not resolve '$key' from $value",
                            )
                        resolvedParams[key] = resolved
                    }
                    Command.Invoke(step.id, step.action, resolvedParams)
                }
            }
            val outcome = runtime.submit(Request(command))
            drain()
            (outcome.result as? CommandResult.Rejected)?.let { return ScenarioResult.Errored(scenario.name, index, it.error) }
        }

        if (overflowed) {
            return ScenarioResult.Malformed(scenario.name, "the scenario produced more events than this run could retain ($EVENT_CAPACITY)")
        }

        val outcomes = scenario.assertions.map { evaluate(it, events, scenario.bound) }
        val snapshots = scenario.assertions.flatMap { relevantIds(it) }.toSet()
            .mapNotNull { id -> inspect(id)?.let { id to it } }.toMap()
        return ScenarioResult.Completed(scenario.name, outcomes, events, snapshots)
    }

    private fun ScenarioStep.advanceCost(): Duration = when (this) {
        is ScenarioStep.Advance -> duration
        is ScenarioStep.Do -> (command as? Command.Advance)?.duration ?: ZERO
        is ScenarioStep.Invoke -> ZERO
    }

    private fun resolve(value: ScenarioValue): Any? = when (value) {
        is ScenarioValue.Literal -> value.value
        is ScenarioValue.AddressOf -> addressOf(value.endpoint)
        is ScenarioValue.MacOf -> macOf(value.endpoint)
    }

    private fun addressOf(endpoint: String): Ipv4Address? {
        val text = (inspect(endpoint)?.state?.get("ipv4") as? Map<*, *>)?.get("address") as? String ?: return null
        return runCatching { Ipv4Address.parse(text) }.getOrNull()
    }

    private fun macOf(endpoint: String): MacAddress? {
        val text = inspect(endpoint)?.state?.get("mac") as? String ?: return null
        return runCatching { MacAddress.parse(text) }.getOrNull()
    }

    private fun inspect(id: String): ObjectSnapshot? {
        val outcome = runtime.submit(Request(Command.Inspect(id)))
        return (outcome.result as? CommandResult.Ok)?.data as? ObjectSnapshot
    }

    private fun relevantIds(assertion: Assertion): List<String> = when (assertion) {
        is AddressInPool -> listOf(assertion.endpoint)
        is LinkIsUp -> listOf(assertion.endpoint)
        is DecisionMatches -> listOf(assertion.source)
        is SnapshotField -> listOf(assertion.id)
        is SnapshotListContains -> listOf(assertion.id)
        is PacketReaches -> emptyList()
        is EventOccurs -> listOfNotNull(assertion.source)
        is ArpRequestsSent -> listOfNotNull(assertion.endpoint, assertion.target)
        is ArpResolved -> listOf(assertion.endpoint, assertion.neighbor)
    }

    private fun evaluate(assertion: Assertion, events: List<EventRecord>, bound: Duration): AssertionOutcome = when (assertion) {
        is AddressInPool -> evaluateAddressInPool(assertion)
        is LinkIsUp -> evaluateLinkIsUp(assertion, events, bound)
        is PacketReaches -> evaluatePacketReaches(assertion, events, bound)
        is DecisionMatches -> evaluateDecisionMatches(assertion, events, bound)
        is SnapshotField -> evaluateSnapshotField(assertion)
        is SnapshotListContains -> evaluateSnapshotListContains(assertion)
        is EventOccurs -> evaluateEventOccurs(assertion, events, bound)
        is ArpRequestsSent -> evaluateArpRequestsSent(assertion, events)
        is ArpResolved -> evaluateArpResolved(assertion)
    }

    private fun evaluateArpRequestsSent(assertion: ArpRequestsSent, events: List<EventRecord>): AssertionOutcome {
        val target = assertion.target?.let { id ->
            addressOf(id) ?: return fail(assertion, "'$id' has no IPv4 address to match requests against")
        }
        val requests = events.filter { record ->
            val arp = (record.payload as? EventPayload.FrameSent)?.frame?.payload as? ArpPacket ?: return@filter false
            record.source.value == assertion.endpoint && arp.operation == ArpOperation.REQUEST && (target == null || arp.targetIp == target)
        }
        val forTarget = if (target != null) " for $target" else ""
        return if (requests.size == assertion.expected) pass(assertion, "${requests.size} ARP request(s)$forTarget sent", requests.take(EVIDENCE_LIMIT))
        else fail(assertion, "'${assertion.endpoint}' sent ${requests.size} ARP request(s)$forTarget, expected ${assertion.expected}", requests.take(EVIDENCE_LIMIT))
    }

    private fun evaluateArpResolved(assertion: ArpResolved): AssertionOutcome {
        val neighbor = inspect(assertion.neighbor) ?: return fail(assertion, "no such object '${assertion.neighbor}'")
        val address = addressOf(assertion.neighbor)?.toString() ?: return fail(assertion, "'${assertion.neighbor}' has no IPv4 address")
        val mac = neighbor.state["mac"]
        val snapshot = inspect(assertion.endpoint) ?: return fail(assertion, "no such object '${assertion.endpoint}'")
        val table = snapshot.state["arp"] as? List<*> ?: return fail(assertion, "'${assertion.endpoint}' has no ARP table")
        val entry = table.firstOrNull { (it as? Map<*, *>)?.get("address") == address } as? Map<*, *>
        return when {
            entry == null -> fail(assertion, "'${assertion.endpoint}' has no ARP entry for $address; table: $table")
            entry["mac"] != mac -> fail(assertion, "'${assertion.endpoint}' maps $address to ${entry["mac"]}, but '${assertion.neighbor}' is $mac")
            else -> pass(assertion, "'${assertion.endpoint}' knows $address is-at $mac")
        }
    }

    private fun evaluateAddressInPool(assertion: AddressInPool): AssertionOutcome {
        val snapshot = inspect(assertion.endpoint) ?: return fail(assertion, "no such object '${assertion.endpoint}'")
        val text = (snapshot.state["ipv4"] as? Map<*, *>)?.get("address") as? String
            ?: return fail(assertion, "'${assertion.endpoint}' has no IPv4 address configured")
        val address = Ipv4Address.parse(text)
        val inPool = Integer.compareUnsigned(address.bits, assertion.start.bits) >= 0 &&
            Integer.compareUnsigned(address.bits, assertion.end.bits) <= 0
        return if (inPool) pass(assertion, "'${assertion.endpoint}' has address $address")
        else fail(assertion, "'${assertion.endpoint}' has address $address, outside ${assertion.start}..${assertion.end}")
    }

    private fun evaluateLinkIsUp(assertion: LinkIsUp, events: List<EventRecord>, bound: Duration): AssertionOutcome {
        val matches = events.filter {
            it.source.value == assertion.endpoint && (it.payload as? EventPayload.LinkStateChanged)?.up == true
        }
        return if (matches.isNotEmpty()) pass(assertion, "link up at t=${matches.first().timeMs}ms", matches.take(EVIDENCE_LIMIT))
        else fail(assertion, "the link at '${assertion.endpoint}' never came up within the scenario's $bound bound")
    }

    private fun evaluatePacketReaches(assertion: PacketReaches, events: List<EventRecord>, bound: Duration): AssertionOutcome {
        val matches = events.filter { record ->
            val accepted = record.payload as? EventPayload.PacketAccepted ?: return@filter false
            accepted.packet.dst == assertion.destination &&
                (assertion.port == null || (accepted.packet.payload as? UdpDatagram)?.dstPort == assertion.port)
        }
        val portSuffix = if (assertion.port != null) ":${assertion.port}" else ""
        return if (matches.isNotEmpty()) pass(assertion, "delivered at t=${matches.first().timeMs}ms", matches.take(EVIDENCE_LIMIT))
        else fail(assertion, "no packet reached ${assertion.destination}$portSuffix within the scenario's $bound bound")
    }

    private fun evaluateDecisionMatches(assertion: DecisionMatches, events: List<EventRecord>, bound: Duration): AssertionOutcome {
        val matches = events.filter { record ->
            val decision = record.payload as? EventPayload.DecisionRecorded ?: return@filter false
            val sourceMatches = record.source.value == assertion.source || record.source.value.startsWith("${assertion.source}.")
            sourceMatches && decision.record.responsibility == assertion.responsibility && decision.record.decision == assertion.decision &&
                assertion.attributes.all { (key, value) -> decision.record.attributes[key] == value }
        }
        return if (matches.isNotEmpty()) pass(assertion, "recorded at t=${matches.first().timeMs}ms", matches.take(EVIDENCE_LIMIT))
        else fail(assertion, "no matching decision was recorded by '${assertion.source}' within the scenario's $bound bound")
    }

    private fun evaluateSnapshotField(assertion: SnapshotField): AssertionOutcome {
        val snapshot = inspect(assertion.id) ?: return fail(assertion, "no such object '${assertion.id}'")
        val actual = navigate(snapshot.state, assertion.path)
        val field = assertion.path.joinToString(".")
        return if (actual == assertion.expected) pass(assertion, "'${assertion.id}'.$field == $actual")
        else fail(assertion, "'${assertion.id}'.$field == $actual, expected ${assertion.expected}")
    }

    private fun evaluateSnapshotListContains(assertion: SnapshotListContains): AssertionOutcome {
        val snapshot = inspect(assertion.id) ?: return fail(assertion, "no such object '${assertion.id}'")
        val field = assertion.path.joinToString(".")
        val list = navigate(snapshot.state, assertion.path) as? List<*>
            ?: return fail(assertion, "'${assertion.id}'.$field is not a list")
        val match = list.any { element -> (element as? Map<*, *>)?.let { map -> assertion.expectedSubset.all { (k, v) -> map[k] == v } } == true }
        return if (match) pass(assertion, "'${assertion.id}'.$field has a matching element")
        else fail(assertion, "no element of '${assertion.id}'.$field matches ${assertion.expectedSubset}; got $list")
    }

    private fun evaluateEventOccurs(assertion: EventOccurs, events: List<EventRecord>, bound: Duration): AssertionOutcome {
        val matches = events.filter { it.type == assertion.type && (assertion.source == null || it.source.value == assertion.source) }
        val sourceSuffix = if (assertion.source != null) " for '${assertion.source}'" else ""
        return if (matches.isNotEmpty()) pass(assertion, "observed at t=${matches.first().timeMs}ms", matches.take(EVIDENCE_LIMIT))
        else fail(assertion, "no ${assertion.type} event$sourceSuffix was observed within the scenario's $bound bound")
    }

    private fun navigate(root: Map<String, Any?>, path: List<String>): Any? {
        var current: Any? = root
        for (key in path) current = (current as? Map<*, *>)?.get(key) ?: return null
        return current
    }

    private fun pass(assertion: Assertion, message: String, evidence: List<EventRecord> = emptyList()) =
        AssertionOutcome.Pass(assertion, message, evidence)

    private fun fail(assertion: Assertion, message: String, evidence: List<EventRecord> = emptyList()) =
        AssertionOutcome.Fail(assertion, message, evidence)

    companion object {
        private const val EVENT_CAPACITY = 200_000
        private const val EVIDENCE_LIMIT = 5
    }
}
