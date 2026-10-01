package com.a2z.nsdl.ip

import com.a2z.nsdl.link.Cable
import com.a2z.nsdl.link.EthernetInterface
import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.model.DropReason
import com.a2z.nsdl.model.EventPayload.FrameDropped
import com.a2z.nsdl.model.EventPayload.FrameReceived
import com.a2z.nsdl.model.EventPayload.NetworkConfigChanged
import com.a2z.nsdl.model.EventPayload.PacketAccepted
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.ConfigSource
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import com.a2z.nsdl.net.Ipv4Packet
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.net.OpaquePayload
import com.a2z.nsdl.net.UdpDatagram
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class Ipv4StackTest {
    private val scheduler = VirtualScheduler()
    private val sink = RecordingSink()
    private val nicA = EthernetInterface(ObjectId("a.eth0"), MacAddress.local(1), sink)
    private val nicB = EthernetInterface(ObjectId("b.eth0"), MacAddress.local(2), sink)
    private val stackA = Ipv4Stack(nicA, sink)
    private val stackB = Ipv4Stack(nicB, sink)
    private val received = mutableListOf<ReceivedDatagram>()

    init {
        nicA.enable(); nicB.enable()
        Cable(ObjectId("c"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, sink).connect(nicA, nicB)
        stackB.bind(67) { received += it }
    }

    @Test
    fun `broadcast UDP travels as IPv4 inside an Ethernet broadcast frame and reaches the bound listener`() {
        assertTrue(stackA.sendUdp(68, Ipv4Address.BROADCAST, 67, OpaquePayload("hello")))
        scheduler.advanceBy(5.milliseconds)

        val d = received.single()
        assertEquals(nicA.mac, d.srcMac)
        assertEquals(Ipv4Address.ANY, d.packet.src, "unconfigured sender uses 0.0.0.0")
        assertEquals(Ipv4Address.BROADCAST, d.packet.dst)
        assertEquals(UdpDatagram(68, 67, OpaquePayload("hello")), d.datagram)

        val frame = sink.of<FrameReceived>(nicB.id).single().frame
        assertEquals(MacAddress.BROADCAST, frame.dst)
        assertEquals(0x0800, frame.etherType)
        assertEquals(1, sink.of<PacketAccepted>(nicB.id).size)
    }

    @Test
    fun `frame received without a bound listener is not accepted as a packet`() {
        stackA.sendUdp(68, Ipv4Address.BROADCAST, 9999, OpaquePayload("nobody"))
        scheduler.advanceBy(5.milliseconds)

        assertEquals(1, sink.of<FrameReceived>(nicB.id).size)
        assertTrue(sink.of<PacketAccepted>(nicB.id).isEmpty())
        assertEquals(DropReason.NO_LISTENER, sink.of<FrameDropped>(nicB.id).single().reason)
    }

    @Test
    fun `unicast to an IP address that is not ours is not accepted`() {
        stackB.applyConfig(Ipv4Config(Ipv4Address.parse("10.0.0.1"), Ipv4Address.parse("255.255.255.0"), source = ConfigSource.STATIC))
        stackA.sendUdp(68, Ipv4Address.parse("10.0.0.2"), 67, OpaquePayload("x"), dstMac = nicB.mac)
        stackA.sendUdp(68, Ipv4Address.parse("10.0.0.1"), 67, OpaquePayload("y"), dstMac = nicB.mac)
        scheduler.advanceBy(5.milliseconds)

        assertEquals(listOf(OpaquePayload("y")), received.map { it.datagram.payload })
    }

    @Test
    fun `a unicast packet that is not ours is handed to the forwarder instead of being dropped`() {
        val forwarded = mutableListOf<Ipv4Packet>()
        val nicD = EthernetInterface(ObjectId("d.eth0"), MacAddress.local(4), sink)
        val nicC = EthernetInterface(ObjectId("c.eth0"), MacAddress.local(3), sink)
        val routedStack = Ipv4Stack(nicC, sink) { _, packet -> forwarded += packet }
        nicD.enable(); nicC.enable()
        Cable(ObjectId("c2"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, sink).connect(nicD, nicC)

        Ipv4Stack(nicD, sink).sendUdp(68, Ipv4Address.parse("192.168.9.9"), 67, OpaquePayload("elsewhere"), dstMac = nicC.mac)
        scheduler.advanceBy(5.milliseconds)

        assertEquals(listOf(Ipv4Address.parse("192.168.9.9")), forwarded.map { it.dst })
        assertTrue(sink.of<FrameDropped>(nicC.id).isEmpty(), "a forwarder present means no NO_LISTENER drop")
        assertTrue(routedStack.config == null, "forwarding does not require local configuration")
    }

    @Test
    fun `applying configuration is observable and inspectable on the interface`() {
        val cfg = Ipv4Config(Ipv4Address.parse("10.0.0.1"), Ipv4Address.parse("255.255.255.0"), source = ConfigSource.STATIC)
        stackA.applyConfig(cfg)
        stackA.applyConfig(cfg)

        assertEquals(listOf(NetworkConfigChanged(cfg)), sink.of<NetworkConfigChanged>(nicA.id), "no event for an unchanged config")
        @Suppress("UNCHECKED_CAST")
        val ipv4 = nicA.snapshot().state["ipv4"] as Map<String, Any?>
        assertEquals("10.0.0.1", ipv4["address"])
        assertEquals("STATIC", ipv4["source"])
    }
}
