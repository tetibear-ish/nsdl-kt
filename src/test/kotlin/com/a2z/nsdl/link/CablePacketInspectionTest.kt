package com.a2z.nsdl.link

import com.a2z.nsdl.model.DropReason
import com.a2z.nsdl.model.EventPayload.PacketObserved
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.TransitOutcome
import com.a2z.nsdl.net.EthernetFrame
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Packet
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.net.OpaquePayload
import com.a2z.nsdl.net.UdpDatagram
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Covers S20's per-cable bounded, ordered history at both the frame and the decoded-packet layer,
 * sharing one id per transmission so the two views never count a transmission twice.
 */
class CablePacketInspectionTest {
    private val scheduler = VirtualScheduler()
    private val sink = RecordingSink()
    private val a = EthernetInterface(ObjectId("a.eth0"), MacAddress.local(1), sink)
    private val b = EthernetInterface(ObjectId("b.eth0"), MacAddress.local(2), sink)

    private fun frame(label: String = "x") = EthernetFrame(
        a.mac, b.mac, Ipv4Packet(Ipv4Address.parse("10.0.0.1"), Ipv4Address.parse("10.0.0.2"), UdpDatagram(68, 67, OpaquePayload(label))),
    )

    private fun upLink(cable: Cable) {
        a.enable(); b.enable(); cable.connect(a, b)
    }

    @Test
    fun `a delivered frame is recorded in both the frame and packet views under one shared id`() {
        val cable = Cable(ObjectId("cable1"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, sink)
        upLink(cable)

        a.send(frame())
        scheduler.advanceBy(LinkProfile.FAST_ETHERNET_100BASE_TX.propagationDelay)

        val frames = cable.snapshot().state["frames"] as List<*>
        val packets = cable.snapshot().state["packets"] as List<*>
        assertEquals(1, frames.size)
        assertEquals(1, packets.size)

        @Suppress("UNCHECKED_CAST") val frameEntry = frames.single() as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val packetEntry = packets.single() as Map<String, Any?>
        assertEquals(frameEntry["id"], packetEntry["id"], "frame and packet views correlate via one shared id")
        assertEquals("a.eth0", frameEntry["from"])
        assertEquals("b.eth0", frameEntry["to"])
        assertEquals("DELIVERED", frameEntry["outcome"])
        assertEquals(null, frameEntry["dropReason"])
        assertEquals("10.0.0.1", packetEntry["sourceIp"])
        assertEquals("10.0.0.2", packetEntry["destIp"])
        assertEquals(68, packetEntry["sourcePort"])
        assertEquals(67, packetEntry["destPort"])
    }

    @Test
    fun `cable emits a PacketObserved event sourced from itself with direction and causal id`() {
        val cable = Cable(ObjectId("cable1"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, sink)
        upLink(cable)

        a.send(frame())
        scheduler.advanceBy(LinkProfile.FAST_ETHERNET_100BASE_TX.propagationDelay)

        val observed = sink.of<PacketObserved>(cable.id).single()
        assertEquals(a.id, observed.from)
        assertEquals(b.id, observed.to)
        assertEquals(TransitOutcome.DELIVERED, observed.outcome)
        assertNull(observed.dropReason)
        assertTrue(observed.transitId.isNotBlank())
    }

    @Test
    fun `a frame dropped in flight is recorded with the drop reason and no packet-layer double counting`() {
        val cable = Cable(ObjectId("cable1"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, sink)
        upLink(cable)

        a.send(frame(label = "lost"))
        cable.disconnect()
        cable.connect(a, b)
        scheduler.advanceBy(10.milliseconds)

        val observed = sink.of<PacketObserved>(cable.id).single()
        assertEquals(TransitOutcome.DROPPED, observed.outcome)
        assertEquals(DropReason.DISCONNECTED_IN_FLIGHT, observed.dropReason)

        @Suppress("UNCHECKED_CAST")
        val frameEntry = (cable.snapshot().state["frames"] as List<Map<String, Any?>>).single()
        assertEquals("DROPPED", frameEntry["outcome"])
        assertEquals("DISCONNECTED_IN_FLIGHT", frameEntry["dropReason"])
    }

    @Test
    fun `retains only the most recent transits once over its bounded capacity`() {
        val cable = Cable(ObjectId("cable1"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, sink, historyCapacity = 2)
        upLink(cable)

        repeat(3) { a.send(frame(label = "f$it")) }
        scheduler.advanceBy(LinkProfile.FAST_ETHERNET_100BASE_TX.propagationDelay)

        @Suppress("UNCHECKED_CAST")
        val frames = cable.snapshot().state["frames"] as List<Map<String, Any?>>
        assertEquals(2, frames.size)
        val allObserved = sink.of<PacketObserved>(cable.id)
        assertEquals(listOf(allObserved[1].transitId, allObserved[2].transitId), frames.map { it["id"] })
    }
}
