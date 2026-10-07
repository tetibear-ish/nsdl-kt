package com.a2z.nsdl.scenario.teaching

import com.a2z.nsdl.model.DecisionAction
import com.a2z.nsdl.model.Responsibility
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.scenario.AddressInPool
import com.a2z.nsdl.scenario.DecisionMatches
import com.a2z.nsdl.scenario.LinkIsUp
import com.a2z.nsdl.scenario.Scenario
import com.a2z.nsdl.scenario.ScenarioValue
import com.a2z.nsdl.scenario.SnapshotListContains
import com.a2z.nsdl.scenario.scenario
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The teaching scenario documented in docs/SCENARIOS.md: a computer and a printer, both acquiring
 * addresses from a gateway's DHCP pool through a learning switch, then the computer submitting a
 * chunked print job directly to the printer, at its DHCP-assigned address read live rather than
 * hard-coded (its MAC is passed too, though the computer could resolve it with ARP). This is the same exchange
 * [com.a2z.nsdl.print.PrintJobIntegrationTest] drives directly against the protocol classes, expressed
 * instead as data a scenario runner can execute from the CLI, the browser or a test.
 */
fun printJobScenario(): Scenario = scenario("print-job", bound = 6.seconds) {
    val poolStart = "10.0.0.100"
    val poolEnd = "10.0.0.110"

    create("computer1", "computer")
    create("printer1", "printer")
    create("gateway1", "gateway", mapOf("address" to "10.0.0.1", "poolStart" to poolStart, "poolEnd" to poolEnd))
    create("switch1", "ethernet-switch")
    create("computer-cable", "cat5-cable")
    create("printer-cable", "cat5-cable")
    create("gateway-cable", "cat5-cable")
    connect("computer-cable", "computer1.eth0", "switch1.port1")
    connect("printer-cable", "printer1.eth0", "switch1.port2")
    connect("gateway-cable", "gateway1.eth0", "switch1.port3")

    powerOn("switch1")
    powerOn("gateway1")
    powerOn("printer1")
    powerOn("computer1")
    advance(5.seconds)

    invoke(
        "computer1.print-client", "submit",
        mapOf(
            "jobId" to ScenarioValue.Literal("job-1"),
            "documentName" to ScenarioValue.Literal("networking-notes.txt"),
            "bytes" to ScenarioValue.Literal(2_500),
            "printerAddress" to ScenarioValue.AddressOf("printer1.eth0"),
            "printerMac" to ScenarioValue.MacOf("printer1.eth0"),
            "chunkSize" to ScenarioValue.Literal(1_000),
        ),
    )
    advance(50.milliseconds)

    val start = Ipv4Address.parse(poolStart)
    val end = Ipv4Address.parse(poolEnd)
    expect(AddressInPool("computer1.eth0", start, end))
    expect(AddressInPool("printer1.eth0", start, end))
    expect(LinkIsUp("printer1.eth0"))
    expect(
        SnapshotListContains(
            "printer1.print-server",
            path = listOf("completedJobs"),
            expectedSubset = mapOf("id" to "job-1", "bytes" to 2_500, "chunks" to 3),
        ),
    )
    expect(DecisionMatches("switch1", Responsibility.SWITCHING, DecisionAction.FORWARD))
}
