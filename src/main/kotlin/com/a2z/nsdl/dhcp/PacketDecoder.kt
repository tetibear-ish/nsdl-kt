package com.a2z.nsdl.dhcp

import com.a2z.nsdl.model.hex
import com.a2z.nsdl.net.EthernetFrame
import com.a2z.nsdl.net.Ipv4Packet
import com.a2z.nsdl.net.UdpDatagram
import com.a2z.nsdl.net.UdpPayload
import com.a2z.nsdl.net.decodeEnvelope

/**
 * Decodes the protocol-level fields the packet-inspection pane presents: the generic IPv4/UDP
 * envelope (see [decodeEnvelope]) plus, when the UDP payload is a typed [DhcpMessage], its message
 * type, transaction id, client address and server identifier. Read-only presentation decode; it
 * never drives simulation behavior.
 */
object PacketDecoder {
    fun decode(frame: EthernetFrame): Map<String, Any?> {
        val udp = (frame.payload as? Ipv4Packet)?.payload as? UdpDatagram ?: return frame.decodeEnvelope()
        return frame.decodeEnvelope() + decodeUdpPayload(udp.payload)
    }

    /** The application-payload-specific fields, independent of the envelope. Empty for unknown payloads. */
    fun decodeUdpPayload(payload: UdpPayload): Map<String, Any?> = when (payload) {
        is DhcpMessage -> mapOf(
            "protocol" to "DHCP",
            "dhcp" to mapOf(
                "messageType" to payload.type.name,
                "transactionId" to "0x" + hex(payload.xid.toLong(), 8),
                "clientAddress" to payload.resolvedClientAddress()?.toString(),
                "serverIdentifier" to payload.serverId?.toString(),
            ),
        )
        else -> emptyMap()
    }
}
