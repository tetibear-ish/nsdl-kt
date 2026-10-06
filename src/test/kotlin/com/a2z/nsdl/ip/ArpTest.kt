package com.a2z.nsdl.ip

import com.a2z.nsdl.link.Cable
import com.a2z.nsdl.link.EthernetInterface
import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.model.DropReason
import com.a2z.nsdl.model.EventPayload.FrameDropped
import com.a2z.nsdl.model.EventPayload.FrameSent
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.ArpOperation
import com.a2z.nsdl.net.ArpPacket
import com.a2z.nsdl.net.ConfigSource
import com.a2z.nsdl.net.EthernetFrame
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.net.OpaquePayload
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class ArpTest {
    private val scheduler = VirtualScheduler()
    private val sink = RecordingSink()
    private val nicA = EthernetInterface(ObjectId("a.eth0"), MacAddress.local(1), sink)
    private val nicB = EthernetInterface(ObjectId("b.eth0"), MacAddress.local(2), sink)
    private val stackA = Ipv4Stack(nicA, sink)
    private val stackB = Ipv4Stack(nicB, sink)
    private val received = mutableListOf<ReceivedDatagram>()
    private val mask = Ipv4Address.parse("255.255.255.0")
    private val addressA = Ipv4Address.parse("10.0.0.1")
    private val addressB = Ipv4Address.parse("10.0.0.2")

    init {
        nicA.enable(); nicB.enable()
        Cable(ObjectId("c"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, sink).connect(nicA, nicB)
        stackA.applyConfig(Ipv4Config(addressA, mask, source = ConfigSource.STATIC))
        stackB.applyConfig(Ipv4Config(addressB, mask, source = ConfigSource.STATIC))
        stackB.bind(9000) { received += it }
    }

    private fun sentBy(nic: EthernetInterface) = sink.of<FrameSent>(nic.id).map { it.frame }

    @Test
    fun `a unicast without a known MAC is queued behind a broadcast ARP request, then delivered unicast`() {
        assertTrue(stackA.sendUdp(1, addressB, 9000, OpaquePayload("first")))
        assertTrue(stackA.sendUdp(1, addressB, 9000, OpaquePayload("second")))

        val request = sentBy(nicA).single()
        assertEquals(MacAddress.BROADCAST, request.dst)
        assertEquals(ArpPacket(ArpOperation.REQUEST, nicA.mac, addressA, MacAddress.ZERO, addressB), request.payload)

        scheduler.advanceBy(5.milliseconds)

        val reply = sentBy(nicB).single()
        assertEquals(nicA.mac, reply.dst, "the reply is unicast to the requester")
        assertEquals(ArpPacket(ArpOperation.REPLY, nicB.mac, addressB, nicA.mac, addressA), reply.payload)
        assertEquals(listOf("first", "second"), received.map { (it.datagram.payload as OpaquePayload).label })
        assertTrue(received.all { it.srcMac == nicA.mac })
        assertEquals(mapOf(addressB to nicB.mac), stackA.arpTable)
        assertEquals(mapOf(addressA to nicA.mac), stackB.arpTable, "the target learns the requester")
    }

    @Test
    fun `a resolved neighbor is reached without another request`() {
        stackA.sendUdp(1, addressB, 9000, OpaquePayload("first"))
        scheduler.advanceBy(5.milliseconds)
        sink.clear()

        stackA.sendUdp(1, addressB, 9000, OpaquePayload("again"))

        val frame = sentBy(nicA).single()
        assertEquals(nicB.mac, frame.dst)
        scheduler.advanceBy(5.milliseconds)
        assertEquals(2, received.size)
    }

    @Test
    fun `requests for another address are ignored and teach nothing`() {
        val nicC = EthernetInterface(ObjectId("c.eth0"), MacAddress.local(3), sink).also { it.enable() }
        val stackC = Ipv4Stack(nicC, sink).also { it.applyConfig(Ipv4Config(Ipv4Address.parse("10.0.0.3"), mask, source = ConfigSource.STATIC)) }

        // A broadcast request for B, as every host on a switched segment would see it.
        nicC.receive(EthernetFrame(nicA.mac, MacAddress.BROADCAST, ArpPacket(ArpOperation.REQUEST, nicA.mac, addressA, MacAddress.ZERO, addressB)))

        assertTrue(stackC.arpTable.isEmpty())
        assertTrue(sentBy(nicC).isEmpty())
    }

    @Test
    fun `the cache is operational state, cleared when the link goes down`() {
        stackA.sendUdp(1, addressB, 9000, OpaquePayload("first"))
        scheduler.advanceBy(5.milliseconds)
        assertEquals(1, stackA.arpTable.size)

        nicA.disable()

        assertTrue(stackA.arpTable.isEmpty())
    }

    @Test
    fun `an off-subnet destination without a router is dropped as NO_ROUTE`() {
        assertFalse(stackA.sendUdp(1, Ipv4Address.parse("192.168.5.5"), 9000, OpaquePayload("lost")))

        assertEquals(DropReason.NO_ROUTE, sink.of<FrameDropped>(nicA.id).single().reason)
    }

    @Test
    fun `an off-subnet destination is sent to the router's MAC`() {
        stackA.applyConfig(Ipv4Config(addressA, mask, router = addressB, source = ConfigSource.STATIC))
        stackB.bind(9001) {}

        stackA.sendUdp(1, Ipv4Address.parse("192.168.5.5"), 9000, OpaquePayload("routed"))

        val request = sentBy(nicA).single().payload as ArpPacket
        assertEquals(addressB, request.targetIp, "ARP asks for the next hop, not the far destination")
    }

    @Test
    fun `an unanswered request drops the oldest queued packet on overflow and asks again`() {
        // The peer has no IP stack, so nothing ever answers.
        val lonely = EthernetInterface(ObjectId("x.eth0"), MacAddress.local(9), sink)
        val peer = EthernetInterface(ObjectId("y.eth0"), MacAddress.local(10), sink)
        lonely.enable(); peer.enable()
        Cable(ObjectId("c3"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, sink).connect(lonely, peer)
        val stack = Ipv4Stack(lonely, sink).also { it.applyConfig(Ipv4Config(addressA, mask, source = ConfigSource.STATIC)) }

        repeat(9) { stack.sendUdp(1, Ipv4Address.parse("10.0.0.77"), 9000, OpaquePayload("p$it")) }

        val requests = sink.of<FrameSent>(lonely.id).filter { it.frame.payload is ArpPacket }
        assertEquals(2, requests.size, "one initial request, one more when the queue overflowed")
        assertEquals(DropReason.ARP_UNRESOLVED, sink.of<FrameDropped>(lonely.id).single().reason)
    }

    @Test
    fun `the stack explains why a destination is unreachable from its own point of view`() {
        assertEquals(null, stackA.explainUnreachable(addressB), "nothing has been tried yet")

        nicB.disable()
        stackA.sendUdp(1, addressB, 9000, OpaquePayload("into the void"))
        nicB.enable()
        assertEquals(null, stackA.explainUnreachable(addressB), "the link flap cleared the pending request")

        val peerless = Ipv4Address.parse("10.0.0.99")
        stackA.sendUdp(1, peerless, 9000, OpaquePayload("hello?"))
        scheduler.advanceBy(5.milliseconds)
        assertEquals("no ARP reply from 10.0.0.99", stackA.explainUnreachable(peerless))
        assertEquals("no route to 192.168.5.5", stackA.explainUnreachable(Ipv4Address.parse("192.168.5.5")))

        nicA.disable()
        assertEquals("the network link is down", stackA.explainUnreachable(peerless))
    }
}
