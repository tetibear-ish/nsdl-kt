package com.a2z.nsdl.scenario.teaching

import com.a2z.nsdl.scenario.ArpRequestsSent
import com.a2z.nsdl.scenario.ArpResolved
import com.a2z.nsdl.scenario.EventOccurs
import com.a2z.nsdl.scenario.Scenario
import com.a2z.nsdl.scenario.ScenarioBuilder
import com.a2z.nsdl.scenario.ScenarioValue
import com.a2z.nsdl.scenario.SnapshotListContains
import com.a2z.nsdl.scenario.scenario
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/*
 * The ARP lesson, one scenario per beat (see "ARP lesson" in docs/SCENARIOS.md). Each starts from the
 * same small office -- a gateway serving DHCP and DNS, a switch, a computer and a printer -- and pings
 * by IPv4 address, so the only address resolution in play is ARP itself.
 */

private const val SETTLE = 5 // seconds: boot plus DHCP for everyone

private fun ScenarioBuilder.office() {
    create("gateway1", "gateway")
    create("switch1", "ethernet-switch")
    create("computer1", "computer")
    create("printer1", "printer")
    listOf("gateway1.eth0", "computer1.eth0", "printer1.eth0").forEachIndexed { i, endpoint ->
        create("cable${i + 1}", "cat5-cable")
        connect("cable${i + 1}", endpoint, "switch1.port${i + 1}")
    }
    listOf("switch1", "gateway1", "computer1", "printer1").forEach(::powerOn)
    advance(SETTLE.seconds)
}

private fun ScenarioBuilder.ping(address: ScenarioValue) = invoke("computer1.ping", "ping", mapOf("address" to address))

private val printer = ScenarioValue.AddressOf("printer1.eth0")

private fun replied(sequence: Int) = SnapshotListContains(
    "computer1.ping", listOf("results"), mapOf("sequence" to sequence, "status" to "REPLIED"),
    description = if (sequence == 1) "the ping is answered" else "ping $sequence is answered",
)

/** Beat 1: the first ping asks the whole segment who has the printer's address; the printer answers. */
fun arpFirstContactScenario(): Scenario = scenario("arp-first-contact", bound = (SETTLE * 1000 + 50).milliseconds) {
    office()
    ping(printer)
    advance(50.milliseconds)

    expect(replied(1))
    expect(ArpRequestsSent("computer1.eth0", 1, target = "printer1.eth0", description = "the computer asks once who has the printer's address"))
    expect(ArpResolved("computer1.eth0", "printer1.eth0", description = "the computer learns the printer's MAC from the reply"))
    expect(ArpResolved("printer1.eth0", "computer1.eth0", description = "the printer learns the computer's MAC from the request itself"))
    expect(ArpRequestsSent("printer1.eth0", 0, description = "so the printer never has to ask to send its ping reply"))
}

/** Beat 2: a second ping finds the answer in the cache and goes straight out. */
fun arpCacheScenario(): Scenario = scenario("arp-cache", bound = (SETTLE * 1000 + 100).milliseconds) {
    office()
    ping(printer)
    advance(50.milliseconds)
    ping(printer)
    advance(50.milliseconds)

    expect(replied(1))
    expect(replied(2))
    expect(ArpRequestsSent("computer1.eth0", 1, description = "two pings, but only one ARP request: the second used the cache"))
}

/** Beat 3: clearing the cache ("arp -d") makes the next ping ask again. */
fun arpForgetScenario(): Scenario = scenario("arp-forget", bound = (SETTLE * 1000 + 100).milliseconds) {
    office()
    ping(printer)
    advance(50.milliseconds)
    invoke("computer1.eth0", "clearArp")
    ping(printer)
    advance(50.milliseconds)

    expect(replied(2))
    expect(ArpRequestsSent("computer1.eth0", 2, description = "the computer asks again after forgetting"))
    expect(ArpRequestsSent("printer1.eth0", 0, description = "the printer still remembers the computer and never asks"))
}

/** Beat 4: nobody has the address, so the question goes unanswered and the ping waits, then times out. */
fun arpUnansweredScenario(): Scenario = scenario("arp-unanswered", bound = (SETTLE * 1000 + 3000).milliseconds) {
    val nobody = "192.168.1.110"
    office()
    ping(ScenarioValue.Literal(nobody))
    advance(3.seconds)

    expect(ArpRequestsSent("computer1.eth0", 1, description = "the computer asks who has $nobody"))
    expect(
        SnapshotListContains(
            "computer1.eth0", listOf("arpPending"), mapOf("address" to nobody, "queued" to 1, "requests" to 1),
            description = "the ping is still held, waiting for an answer that never comes",
        ),
    )
    expect(
        SnapshotListContains(
            "computer1.ping", listOf("results"), mapOf("status" to "TIMED_OUT", "detail" to "timed out: no ARP reply from $nobody"),
            description = "the ping times out, and says why",
        ),
    )
}

/** Beat 5: a destination off the local network is reached through the router, so ARP asks for the router. */
fun arpViaRouterScenario(): Scenario = scenario("arp-via-router", bound = (SETTLE * 1000 + 3000).milliseconds) {
    val remote = "8.8.8.8"
    office()
    ping(ScenarioValue.Literal(remote))
    advance(3.seconds)

    expect(ArpRequestsSent("computer1.eth0", 1, target = "gateway1.eth0", description = "the computer asks for the router's MAC, not $remote's"))
    expect(ArpRequestsSent("computer1.eth0", 1, description = "and asks nothing else"))
    expect(ArpResolved("computer1.eth0", "gateway1.eth0"))
    expect(
        SnapshotListContains(
            "computer1.ping", listOf("results"), mapOf("status" to "TIMED_OUT", "detail" to "timed out: $remote did not respond"),
            description = "the ping left the computer; this small gateway just doesn't route it anywhere",
        ),
    )
}

/**
 * Printing by name through the software layer: one user action that takes a DNS lookup (and so an ARP
 * request for the DNS server) before the printer's own ARP request and the print exchange.
 */
fun printByNameScenario(): Scenario = scenario("print-by-name", bound = (SETTLE * 1000 + 1000).milliseconds) {
    office()
    invoke(
        "computer1.print-spooler", "print",
        mapOf("document" to ScenarioValue.Literal("minutes.txt"), "printer" to ScenarioValue.Literal("printer1"), "pages" to ScenarioValue.Literal(2)),
    )
    advance(1.seconds)

    expect(
        SnapshotListContains(
            "computer1.print-spooler", listOf("jobs"),
            mapOf("id" to "job-1", "status" to "ACCEPTED", "detail" to "accepted by printer1"),
            description = "the user's job is accepted by the printer they named",
        ),
    )
    expect(
        SnapshotListContains(
            "printer1.print-server", listOf("completedJobs"), mapOf("id" to "computer1/job-1", "bytes" to 6_000),
            description = "the printer received the whole job under the same name",
        ),
    )
    expect(EventOccurs("ProtocolStateChanged", "computer1.dns-resolver", description = "the name was looked up with DNS"))
    expect(ArpRequestsSent("computer1.eth0", 1, target = "gateway1.eth0", description = "ARP first finds the DNS server"))
    expect(ArpRequestsSent("computer1.eth0", 1, target = "printer1.eth0", description = "then ARP finds the printer"))
}
