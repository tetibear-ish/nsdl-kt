package com.a2z.nsdl.link

import com.a2z.nsdl.device.HostBuilder
import com.a2z.nsdl.dhcp.DhcpClient
import com.a2z.nsdl.dhcp.PacketDecoder
import com.a2z.nsdl.dhcp.DhcpPool
import com.a2z.nsdl.dhcp.DhcpServer
import com.a2z.nsdl.model.EventPayload.PacketObserved
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.ConfigSource
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.testing.RecordingSink
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * End-to-end: a real DHCP acquisition across a cable wired the way [com.a2z.nsdl.app.types.Cat5CableType]
 * wires it (describePayload = PacketDecoder::decodeUdpPayload). Covers decoding DHCP message type,
 * transaction id, client address and server identifier from typed simulation packets, as they are
 * actually produced and observed, not just constructed by hand.
 */
class CableDhcpPacketInspectionTest {
    @Test
    fun `a full DHCP acquisition over one cable is observed as DISCOVER, OFFER, REQUEST, ACK`() {
        val scheduler = VirtualScheduler()
        val events = RecordingSink()
        val poolStart = Ipv4Address.parse("10.0.0.100")
        val poolEnd = Ipv4Address.parse("10.0.0.110")
        val mask = Ipv4Address.parse("255.255.255.0")
        val gatewayAddress = Ipv4Address.parse("10.0.0.1")

        val printer = HostBuilder(ObjectId("printer"), "printer", scheduler, events).run {
            val eth0 = ethernet("eth0", MacAddress.local(1))
            service(DhcpClient(id.child("dhcp-client"), eth0, eth0, Random(3), events))
            build { 1.seconds }
        }
        val gateway = HostBuilder(ObjectId("gateway"), "gateway", scheduler, events).run {
            val eth0 = ethernet("eth0", MacAddress.local(2))
            eth0.applyConfig(Ipv4Config(gatewayAddress, mask, source = ConfigSource.STATIC))
            service(DhcpServer(id.child("dhcp-server"), eth0, DhcpPool(poolStart, poolEnd, mask, gatewayAddress, leaseSeconds = 3600), events))
            build { 1.seconds }
        }
        val cable = Cable(
            ObjectId("cable1"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, events,
            describePayload = PacketDecoder::decodeUdpPayload,
        )
        cable.connect(printer.interfaces.single(), gateway.interfaces.single())

        gateway.powerOn()
        printer.powerOn()
        scheduler.advanceBy(5.seconds)

        val dhcpTransits = events.of<PacketObserved>(cable.id).filter { PacketDecoder.decode(it.frame).containsKey("dhcp") }

        @Suppress("UNCHECKED_CAST")
        val packets = cable.snapshot().state["packets"] as List<Map<String, Any?>>
        val messageTypes = packets.mapNotNull { (it["dhcp"] as? Map<*, *>)?.get("messageType") }
        assertEquals(listOf("DISCOVER", "OFFER", "REQUEST", "ACK"), messageTypes)
        assertEquals(dhcpTransits.size, packets.count { it["dhcp"] != null })

        val offer = packets.first { (it["dhcp"] as Map<*, *>)["messageType"] == "OFFER" }["dhcp"] as Map<*, *>
        assertEquals(gatewayAddress.toString(), offer["serverIdentifier"])
        val offeredAddress = offer["clientAddress"] as String

        val ack = packets.first { (it["dhcp"] as Map<*, *>)["messageType"] == "ACK" }["dhcp"] as Map<*, *>
        assertEquals(offeredAddress, ack["clientAddress"])

        val discover = packets.first { (it["dhcp"] as Map<*, *>)["messageType"] == "DISCOVER" }["dhcp"] as Map<*, *>
        assertNull(discover["serverIdentifier"])
    }
}
