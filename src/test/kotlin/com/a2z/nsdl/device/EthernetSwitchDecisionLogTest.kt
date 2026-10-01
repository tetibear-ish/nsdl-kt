package com.a2z.nsdl.device

import com.a2z.nsdl.model.DecisionAction
import com.a2z.nsdl.model.EventPayload.DecisionRecorded
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.EthernetFrame
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Packet
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.net.OpaquePayload
import com.a2z.nsdl.net.UdpDatagram
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.testing.RecordingSink
import kotlin.time.Duration.Companion.ZERO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EthernetSwitchDecisionLogTest {
    private val scheduler = VirtualScheduler()
    private val events = RecordingSink()
    private val networkSwitch = EthernetSwitch(
        ObjectId("switch1"),
        portCount = 3,
        scheduler = scheduler,
        events = events,
        decisionCapacity = 3,
    )
    private val hostA = MacAddress.local(101)
    private val hostB = MacAddress.local(102)

    @Test
    fun `switch emits typed learning flood forward and same-port drop decisions`() {
        networkSwitch.powerOn()
        scheduler.advanceBy(ZERO)

        networkSwitch.ports[0].receive(frame(hostA, MacAddress.BROADCAST))
        networkSwitch.ports[1].receive(frame(hostB, hostA))
        networkSwitch.ports[1].receive(frame(hostB, hostB))

        val records = events.of<DecisionRecorded>(networkSwitch.id).map { it.record }
        assertEquals(
            listOf(
                DecisionAction.LEARN_SOURCE,
                DecisionAction.FLOOD,
                DecisionAction.LEARN_SOURCE,
                DecisionAction.FORWARD,
                DecisionAction.LEARN_SOURCE,
                DecisionAction.DROP,
            ),
            records.map { it.decision },
        )
        assertEquals("switch1:decision:1", records.first().id)
        assertEquals("switch1.port1", records[1].attributes["ingress"])
        assertEquals("broadcast destination", records[1].reason)
        assertEquals("switch1.port1", records[3].attributes["egress"])
        assertEquals("destination learned on ingress port", records.last().reason)
        assertNull(records.first().parents.intentionId)
    }

    @Test
    fun `switch inspection retains only bounded deterministic decision records`() {
        networkSwitch.powerOn()
        scheduler.advanceBy(ZERO)

        networkSwitch.ports[0].receive(frame(hostA, MacAddress.BROADCAST))
        networkSwitch.ports[1].receive(frame(hostB, hostA))

        @Suppress("UNCHECKED_CAST")
        val inspected = networkSwitch.snapshot().state["decisions"] as List<Map<String, Any?>>
        assertEquals(listOf("switch1:decision:2", "switch1:decision:3", "switch1:decision:4"), inspected.map { it["id"] })
        assertEquals(listOf("FLOOD", "LEARN_SOURCE", "FORWARD"), inspected.map { it["decision"] })
        assertEquals("switching", inspected.last()["responsibility"])
    }

    private fun frame(src: MacAddress, dst: MacAddress) = EthernetFrame(
        src,
        dst,
        Ipv4Packet(
            Ipv4Address.ANY,
            Ipv4Address.BROADCAST,
            UdpDatagram(1, 2, OpaquePayload("test")),
        ),
    )
}
