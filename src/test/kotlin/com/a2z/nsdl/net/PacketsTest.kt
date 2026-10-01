package com.a2z.nsdl.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Covers the generic protocol-envelope decode the packet-inspection pane and per-cable history use. */
class PacketsTest {
    private val mac1 = MacAddress.local(1)
    private val mac2 = MacAddress.local(2)

    @Test
    fun `decodes source and destination address and protocol for a UDP-carrying frame`() {
        val frame = EthernetFrame(
            mac1, mac2,
            Ipv4Packet(Ipv4Address.parse("10.0.0.1"), Ipv4Address.parse("10.0.0.2"), UdpDatagram(68, 67, OpaquePayload("x"))),
        )
        assertEquals(
            mapOf(
                "protocol" to "UDP",
                "sourceIp" to "10.0.0.1",
                "destIp" to "10.0.0.2",
                "sourcePort" to 68,
                "destPort" to 67,
            ),
            frame.decodeEnvelope(),
        )
    }
}
