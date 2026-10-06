package com.a2z.nsdl.net

/*
 * Behavioral message model: immutable typed layers, not wire-compatible byte serialization.
 * Ethernet frame -> IPv4 packet -> UDP datagram -> application payload (e.g. DHCP),
 * or Ethernet frame -> ARP packet.
 */

sealed interface EthernetPayload {
    val etherType: Int
    fun describe(): String
}

sealed interface Ipv4Payload {
    val protocolNumber: Int
    fun describe(): String
}

enum class ArpOperation { REQUEST, REPLY }

/**
 * Address Resolution Protocol (RFC 826) for IPv4 over Ethernet: "who has [targetIp]?" is broadcast,
 * and the owner answers with its hardware address. [targetMac] is [MacAddress.ZERO] in a request.
 */
data class ArpPacket(
    val operation: ArpOperation,
    val senderMac: MacAddress,
    val senderIp: Ipv4Address,
    val targetMac: MacAddress,
    val targetIp: Ipv4Address,
) : EthernetPayload {
    override val etherType get() = 0x0806
    override fun describe() = when (operation) {
        ArpOperation.REQUEST -> "ARP who-has $targetIp tell $senderIp"
        ArpOperation.REPLY -> "ARP $senderIp is-at $senderMac"
    }
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

/**
 * Generic protocol-envelope fields for presentation (packet inspection, per-cable history): IPv4
 * addresses and, for UDP, ports. Read-only decode of the typed model; it never drives simulation
 * behavior and knows nothing about application-layer payloads (e.g. DHCP) -- that decode lives
 * alongside the typed payload itself, higher up the dependency chain.
 */
fun EthernetFrame.decodeEnvelope(): Map<String, Any?> = when (val p = payload) {
    is Ipv4Packet -> {
        val base = mapOf("protocol" to "IPv4", "sourceIp" to p.src.toString(), "destIp" to p.dst.toString())
        when (val inner = p.payload) {
            is UdpDatagram -> base + mapOf("protocol" to "UDP", "sourcePort" to inner.srcPort, "destPort" to inner.dstPort)
        }
    }
    is ArpPacket -> mapOf(
        "protocol" to "ARP",
        "sourceIp" to p.senderIp.toString(),
        "destIp" to p.targetIp.toString(),
        "arp" to mapOf("operation" to p.operation.name, "senderMac" to p.senderMac.toString(), "targetMac" to p.targetMac.toString()),
    )
}
