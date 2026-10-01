package com.a2z.nsdl.dhcp

import com.a2z.nsdl.net.EthernetFrame
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Packet
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.net.OpaquePayload
import com.a2z.nsdl.net.UdpDatagram
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** Covers decoding DHCP message type, transaction id, client address and server identifier (S20). */
class PacketDecoderTest {
    private val mac1 = MacAddress.local(1)
    private val mac2 = MacAddress.local(2)

    @Test
    fun `decodes generic UDP fields plus DHCP message type, xid, client address and server identifier`() {
        val discover = DhcpMessage(
            BootOp.REQUEST, DhcpMessageType.DISCOVER, xid = 0x12345678, chaddr = mac1,
            requestedIp = Ipv4Address.parse("10.0.0.7"),
        )
        val frame = EthernetFrame(mac1, MacAddress.BROADCAST, Ipv4Packet(Ipv4Address.ANY, Ipv4Address.BROADCAST, UdpDatagram(68, 67, discover)))

        val decoded = PacketDecoder.decode(frame)

        assertEquals("DHCP", decoded["protocol"])
        assertEquals(68, decoded["sourcePort"])
        assertEquals(67, decoded["destPort"])
        @Suppress("UNCHECKED_CAST")
        val dhcp = decoded["dhcp"] as Map<String, Any?>
        assertEquals("DISCOVER", dhcp["messageType"])
        assertEquals("0x12345678", dhcp["transactionId"])
        assertEquals("10.0.0.7", dhcp["clientAddress"])
        assertEquals(null, dhcp["serverIdentifier"])
    }

    @Test
    fun `decodes an offer's server identifier`() {
        val offer = DhcpMessage(
            BootOp.REPLY, DhcpMessageType.OFFER, xid = 1, chaddr = mac1,
            yiaddr = Ipv4Address.parse("10.0.0.100"),
            serverId = Ipv4Address.parse("10.0.0.1"),
        )
        val frame = EthernetFrame(mac2, mac1, Ipv4Packet(Ipv4Address.parse("10.0.0.1"), Ipv4Address.BROADCAST, UdpDatagram(67, 68, offer)))

        val decoded = PacketDecoder.decode(frame)

        @Suppress("UNCHECKED_CAST")
        val dhcp = decoded["dhcp"] as Map<String, Any?>
        assertEquals("OFFER", dhcp["messageType"])
        assertEquals("10.0.0.100", dhcp["clientAddress"])
        assertEquals("10.0.0.1", dhcp["serverIdentifier"])
    }

    @Test
    fun `leaves protocol as UDP and has no dhcp key for a non-DHCP UDP payload`() {
        val frame = EthernetFrame(mac1, mac2, Ipv4Packet(Ipv4Address.ANY, Ipv4Address.BROADCAST, UdpDatagram(9, 10, OpaquePayload("x"))))

        val decoded = PacketDecoder.decode(frame)

        assertEquals("UDP", decoded["protocol"])
        assertFalse(decoded.containsKey("dhcp"))
    }
}
