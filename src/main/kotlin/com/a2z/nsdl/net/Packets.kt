package com.a2z.nsdl.net

/*
 * Behavioral message model: immutable typed layers, not wire-compatible byte serialization.
 * Ethernet frame -> IPv4 packet -> UDP datagram -> application payload (e.g. DHCP).
 */

sealed interface EthernetPayload {
    val etherType: Int
    fun describe(): String
}

sealed interface Ipv4Payload {
    val protocolNumber: Int
    fun describe(): String
}

/** Application payload carried in a UDP datagram. */
interface UdpPayload {
    fun describe(): String
}

data class EthernetFrame(val src: MacAddress, val dst: MacAddress, val payload: EthernetPayload) {
    val etherType get() = payload.etherType
    fun describe() = "ETH $src > $dst ${payload.describe()}"
}

data class Ipv4Packet(
    val src: Ipv4Address,
    val dst: Ipv4Address,
    val payload: Ipv4Payload,
    val ttl: Int = 64,
) : EthernetPayload {
    override val etherType get() = 0x0800
    override fun describe() = "IPv4 $src > $dst ${payload.describe()}"
}

data class UdpDatagram(val srcPort: Int, val dstPort: Int, val payload: UdpPayload) : Ipv4Payload {
    init { require(srcPort in 0..65535 && dstPort in 0..65535) { "invalid UDP port" } }
    override val protocolNumber get() = 17
    override fun describe() = "UDP $srcPort > $dstPort ${payload.describe()}"
}

/** Opaque payload for tests and future protocols. */
data class OpaquePayload(val label: String) : UdpPayload {
    override fun describe() = "DATA($label)"
}
