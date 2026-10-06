package com.a2z.nsdl.dns

import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.UdpPayload

/**
 * A deliberately small DNS-*shaped* teaching protocol over UDP: one question (an A record for a
 * host name) and one answer. There are no zones, record types other than A, recursion, TTLs,
 * compression, or wire format -- see docs/ARCHITECTURE.md for what this models.
 */
object DnsProtocol {
    const val SERVER_PORT = 53
    const val CLIENT_PORT = 49153

    /** Host names compare case-insensitively and without a trailing root dot. */
    fun normalize(name: String): String = name.trim().trimEnd('.').lowercase()
}

enum class DnsStatus { NOERROR, NXDOMAIN }

sealed interface DnsMessage : UdpPayload {
    val queryId: Int
    val name: String

    data class Query(override val queryId: Int, override val name: String) : DnsMessage {
        override fun describe() = "DNS QUERY id=$queryId A? $name"
    }

    data class Answer(
        override val queryId: Int,
        override val name: String,
        val status: DnsStatus,
        val address: Ipv4Address?,
    ) : DnsMessage {
        override fun describe() = when (status) {
            DnsStatus.NOERROR -> "DNS ANSWER id=$queryId $name A $address"
            DnsStatus.NXDOMAIN -> "DNS ANSWER id=$queryId $name NXDOMAIN"
        }
    }
}
