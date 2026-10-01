package com.a2z.nsdl.scenario.teaching

import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.scenario.AddressInPool
import com.a2z.nsdl.scenario.Scenario
import com.a2z.nsdl.scenario.ScenarioValue
import com.a2z.nsdl.scenario.SnapshotField
import com.a2z.nsdl.scenario.SnapshotListContains
import com.a2z.nsdl.scenario.scenario
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The SSH teaching scenario documented in docs/SCENARIOS.md: a computer and a Linux host acquire
 * addresses from a gateway's DHCP pool through a learning switch, then the computer opens an SSH
 * session to the host -- version hello, password auth, running `neofetch`, and transferring one file
 * -- addressing the host live (no ARP, same as the print job scenario) rather than hard-coding it.
 * See [com.a2z.nsdl.ssh.SshClient]/[com.a2z.nsdl.ssh.SshServer] for what this protocol does and does
 * not model.
 */
fun sshSessionScenario(): Scenario = scenario("ssh-session", bound = 6.seconds) {
    val poolStart = "10.0.1.100"
    val poolEnd = "10.0.1.110"

    create("computer1", "computer")
    create("server1", "linux-host", mapOf("username" to "student", "password" to "hunter2"))
    create("gateway1", "gateway", mapOf("address" to "10.0.1.1", "poolStart" to poolStart, "poolEnd" to poolEnd))
    create("switch1", "ethernet-switch")
    create("computer-cable", "cat5-cable")
    create("server-cable", "cat5-cable")
    create("gateway-cable", "cat5-cable")
    connect("computer-cable", "computer1.eth0", "switch1.port1")
    connect("server-cable", "server1.eth0", "switch1.port2")
    connect("gateway-cable", "gateway1.eth0", "switch1.port3")

    powerOn("switch1")
    powerOn("gateway1")
    powerOn("server1")
    powerOn("computer1")
    advance(5.seconds)

    invoke(
        "computer1.ssh-client", "openSession",
        mapOf(
            "username" to ScenarioValue.Literal("student"),
            "password" to ScenarioValue.Literal("hunter2"),
            "command" to ScenarioValue.Literal("neofetch"),
            "fileName" to ScenarioValue.Literal("report.txt"),
            "fileBytes" to ScenarioValue.Literal(3_000),
            "serverAddress" to ScenarioValue.AddressOf("server1.eth0"),
            "serverMac" to ScenarioValue.MacOf("server1.eth0"),
        ),
    )
    advance(50.milliseconds)

    val start = Ipv4Address.parse(poolStart)
    val end = Ipv4Address.parse(poolEnd)
    expect(AddressInPool("computer1.eth0", start, end))
    expect(AddressInPool("server1.eth0", start, end))
    expect(SnapshotField("computer1.ssh-client", path = listOf("session", "status"), expected = "CLOSED"))
    expect(
        SnapshotField(
            "computer1.ssh-client",
            path = listOf("session", "output", "exitCode"),
            expected = 0,
            description = "the session's 'neofetch' command exits 0",
        ),
    )
    expect(
        SnapshotListContains(
            "server1.ssh-server",
            path = listOf("completedFiles"),
            expectedSubset = mapOf("name" to "report.txt", "bytes" to 3_000, "chunks" to 3),
        ),
    )
}
