package com.a2z.nsdl.dhcp

import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.net.UdpPayload
import com.a2z.nsdl.model.hex

enum class BootOp { REQUEST, REPLY }

/** DHCP message type (option 53). */
enum class DhcpMessageType { DISCOVER, OFFER, REQUEST, DECLINE, ACK, NAK, RELEASE, INFORM }

/**
 * Typed DHCPv4 message (RFC 2131 section 2), carrying only the fields and options this simulation uses:
 * option 50 [requestedIp], 51 [leaseSeconds], 54 [serverId], 1 [subnetMask], 3 [router],
 * 6 [dnsServer], and 12 [hostname] (the name a client asks to be known by).
 * The client is identified by [chaddr]; option 61 (client identifier) is not modeled.
 */
data class DhcpMessage(
    val op: BootOp,
    val type: DhcpMessageType,
    val xid: Int,
    val chaddr: MacAddress,
    val ciaddr: Ipv4Address = Ipv4Address.ANY,
    val yiaddr: Ipv4Address = Ipv4Address.ANY,
    val siaddr: Ipv4Address = Ipv4Address.ANY,
    /** BROADCAST flag: the client cannot yet receive unicast, so replies must be broadcast. */
    val broadcast: Boolean = true,
    val requestedIp: Ipv4Address? = null,
    val serverId: Ipv4Address? = null,
    val leaseSeconds: Long? = null,
    val subnetMask: Ipv4Address? = null,
    val router: Ipv4Address? = null,
    val dnsServer: Ipv4Address? = null,
    val hostname: String? = null,
) : UdpPayload {
    override fun describe(): String = buildString {
        append("DHCP").append(type).append(" xid=0x").append(hex(xid.toLong(), 8)).append(" chaddr=").append(chaddr)
        if (!yiaddr.isUnspecified) append(" yiaddr=").append(yiaddr)
        requestedIp?.let { append(" requested=").append(it) }
        serverId?.let { append(" server=").append(it) }
        hostname?.let { append(" hostname=").append(it) }
    }

    /**
     * The client address this message is most relevantly about, for presentation (e.g. packet
     * inspection): the server's assignment ([yiaddr]) takes priority, then the client's own current
     * address when renewing ([ciaddr]), then what it is requesting (option 50). Null if none apply.
     */
    fun resolvedClientAddress(): Ipv4Address? = when {
        !yiaddr.isUnspecified -> yiaddr
        !ciaddr.isUnspecified -> ciaddr
        else -> requestedIp
    }

    companion object {
        const val SERVER_PORT = 67
        const val CLIENT_PORT = 68
    }
}
