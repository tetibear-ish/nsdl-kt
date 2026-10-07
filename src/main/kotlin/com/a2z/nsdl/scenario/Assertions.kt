package com.a2z.nsdl.scenario

import com.a2z.nsdl.model.DecisionAction
import com.a2z.nsdl.model.Responsibility
import com.a2z.nsdl.net.Ipv4Address

/**
 * Something a [Scenario] claims will be true by the time its steps finish running. [ScenarioRunner]
 * evaluates every kind against the complete accumulated event trace and/or final object snapshots, not
 * just one sampled instant, so it doesn't matter how coarsely a scenario's Advance steps are chunked.
 */
sealed interface Assertion {
    val description: String
}

/** An outcome assertion: the endpoint's final IPv4 address falls within the declared pool, inclusive. */
data class AddressInPool(
    val endpoint: String,
    val start: Ipv4Address,
    val end: Ipv4Address,
    override val description: String = "'$endpoint' has an address in $start..$end",
) : Assertion

/** An outcome assertion: the endpoint's link became operationally up at some point during the run. */
data class LinkIsUp(
    val endpoint: String,
    override val description: String = "the link at '$endpoint' comes up",
) : Assertion

/** An outcome assertion: an IPv4/UDP packet was accepted at [destination] (and [port], if given). */
data class PacketReaches(
    val destination: Ipv4Address,
    val port: Int? = null,
    override val description: String = "a packet reaches $destination" + (if (port != null) ":$port" else ""),
) : Assertion

/**
 * A causal assertion: a [com.a2z.nsdl.model.DecisionRecord] was emitted by [source] (or one of its
 * children) with the given [responsibility] and [decision], and whose attributes are a superset of
 * [attributes] -- e.g. requiring a switch's MAC-table FORWARD decision, per PLAN.md's S23.
 */
data class DecisionMatches(
    val source: String,
    val responsibility: Responsibility,
    val decision: DecisionAction,
    val attributes: Map<String, String> = emptyMap(),
    override val description: String = "'$source' records a $responsibility/$decision decision" +
        (if (attributes.isNotEmpty()) " with $attributes" else ""),
) : Assertion

/** A general outcome assertion: the final snapshot of [id], navigated by [path], equals [expected]. */
data class SnapshotField(
    val id: String,
    val path: List<String>,
    val expected: Any?,
    override val description: String = "'$id'.${path.joinToString(".")} == $expected",
) : Assertion

/**
 * A general outcome assertion: the final snapshot of [id], navigated by [path], is a list containing
 * at least one element (itself a map) whose entries are a superset of [expectedSubset]. Used for
 * collection-shaped state such as a print server's completed jobs or an SSH server's received files.
 */
data class SnapshotListContains(
    val id: String,
    val path: List<String>,
    val expectedSubset: Map<String, Any?>,
    override val description: String = "'$id'.${path.joinToString(".")} contains an element matching $expectedSubset",
) : Assertion

/** A general assertion: at least one event of [type] occurred (optionally scoped to [source]). */
data class EventOccurs(
    val type: String,
    val source: String? = null,
    override val description: String = "a $type event occurs" + (if (source != null) " for '$source'" else ""),
) : Assertion

/**
 * A lesson assertion: [endpoint] broadcast exactly [expected] ARP requests during the run, counting
 * only requests for [target]'s address when given (an endpoint id such as "gateway1.eth0", read when
 * the assertion is evaluated). Distinguishes "asked once, then remembered" from "asked every time".
 */
data class ArpRequestsSent(
    val endpoint: String,
    val expected: Int,
    val target: String? = null,
    override val description: String =
        "'$endpoint' sends exactly $expected ARP request${if (expected == 1) "" else "s"}" + (if (target != null) " for '$target'" else ""),
) : Assertion

/**
 * A lesson assertion: by the end of the run, [endpoint]'s ARP table maps [neighbor]'s IPv4 address to
 * [neighbor]'s MAC address (both endpoint ids, e.g. "computer1.eth0" and "printer1.eth0").
 */
data class ArpResolved(
    val endpoint: String,
    val neighbor: String,
    override val description: String = "'$endpoint' has resolved '$neighbor' in its ARP table",
) : Assertion
