package com.a2z.nsdl.dhcp

import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.MacAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Covers the decode helper the packet-inspection pane uses to show "client address" per message. */
class DhcpMessageTest {
    private val chaddr = MacAddress.local(1)

    @Test
    fun `resolved client address prefers the server-assigned yiaddr`() {
        val ack = DhcpMessage(
            BootOp.REPLY, DhcpMessageType.ACK, xid = 1, chaddr = chaddr,
            yiaddr = Ipv4Address.parse("10.0.0.5"),
            ciaddr = Ipv4Address.parse("10.0.0.9"),
            requestedIp = Ipv4Address.parse("10.0.0.7"),
        )
        assertEquals(Ipv4Address.parse("10.0.0.5"), ack.resolvedClientAddress())
    }

    @Test
    fun `resolved client address falls back to ciaddr when renewing with no yiaddr`() {
        val renewal = DhcpMessage(
            BootOp.REQUEST, DhcpMessageType.REQUEST, xid = 2, chaddr = chaddr,
            ciaddr = Ipv4Address.parse("10.0.0.9"),
        )
        assertEquals(Ipv4Address.parse("10.0.0.9"), renewal.resolvedClientAddress())
    }

    @Test
    fun `resolved client address falls back to the requested address during discovery`() {
        val discover = DhcpMessage(
            BootOp.REQUEST, DhcpMessageType.DISCOVER, xid = 3, chaddr = chaddr,
            requestedIp = Ipv4Address.parse("10.0.0.7"),
        )
        assertEquals(Ipv4Address.parse("10.0.0.7"), discover.resolvedClientAddress())
    }

    @Test
    fun `resolved client address is null when nothing identifies one`() {
        val discover = DhcpMessage(BootOp.REQUEST, DhcpMessageType.DISCOVER, xid = 4, chaddr = chaddr)
        assertNull(discover.resolvedClientAddress())
    }
}
