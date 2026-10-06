package com.a2z.nsdl.ip

import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Packet
import com.a2z.nsdl.net.UdpDatagram

/** A static inbound rule: UDP arriving at the outside address on [outsidePort] goes to [insideAddress]:[insidePort]. */
data class PortForward(val outsidePort: Int, val insideAddress: Ipv4Address, val insidePort: Int) {
    init { require(outsidePort in 1..65535 && insidePort in 1..65535) { "invalid port in port forward" } }

    override fun toString() = "$outsidePort>$insideAddress:$insidePort"

    companion object {
        /** Parses "8080>192.168.1.50:80, 2222>192.168.1.60:22"; blank is no rules. Null if any entry is malformed. */
        fun parseList(text: String): List<PortForward>? = text.split(',').map(String::trim).filter(String::isNotEmpty).map { entry ->
            val outside = entry.substringBefore('>', "").toIntOrNull()
            val inside = entry.substringAfter('>', "")
            val address = runCatching { Ipv4Address.parse(inside.substringBefore(':')) }.getOrNull()
            val insidePort = inside.substringAfter(':', "").toIntOrNull()
            if (outside == null || address == null || insidePort == null) return null
            runCatching { PortForward(outside, address, insidePort) }.getOrNull() ?: return null
        }.also { rules -> if (rules.map { it.outsidePort }.toSet().size != rules.size) return null }
    }
}

/** Which router interfaces face the private network ([inside]) and the public one ([outside]). */
data class NatConfig(val inside: ObjectId, val outside: ObjectId, val portForwards: List<PortForward> = emptyList())

/**
 * Network address and port translation ("masquerading") for UDP, as a home router does it:
 *
 * - Outbound, a packet from inside leaving through the outside interface gets the outside address as
 *   its source and a port from this table: the same inside address and port always reuse one mapping.
 * - Inbound, a packet to the outside address is translated back only if its destination port has a
 *   mapping and, for a dynamic mapping, its source is a peer the inside host has already sent to
 *   (port-restricted filtering). Static [PortForward]s accept any peer.
 *
 * Dynamic mappings have no idle timeout in this model; they are cleared when either interface's link
 * goes down (e.g. at power-off). Hairpinning (inside to its own outside address) is not modeled.
 */
class NatTable(private val portForwards: List<PortForward>, private val firstDynamicPort: Int = 50_000) {
    private class Mapping(val outsidePort: Int, val insideAddress: Ipv4Address, val insidePort: Int, val static: Boolean) {
        val peers = linkedSetOf<Pair<Ipv4Address, Int>>()
    }

    private val byOutsidePort = linkedMapOf<Int, Mapping>()
    private val byInside = mutableMapOf<Pair<Ipv4Address, Int>, Mapping>()
    private var nextPort = firstDynamicPort

    init { reset() }

    data class Translation(val packet: Ipv4Packet, val created: Boolean)

    /** Rewrites [packet]'s source to [outsideAddress] and a mapped port. */
    fun outbound(packet: Ipv4Packet, outsideAddress: Ipv4Address): Translation? {
        val udp = packet.payload as? UdpDatagram ?: return null
        val inside = packet.src to udp.srcPort
        var created = false
        val mapping = byInside[inside] ?: allocate(packet.src, udp.srcPort).also { created = true }
        mapping.peers += packet.dst to udp.dstPort
        return Translation(packet.copy(src = outsideAddress, payload = udp.copy(srcPort = mapping.outsidePort)), created)
    }

    /** Rewrites [packet]'s destination back to the inside host, or returns null when nothing matches. */
    fun inbound(packet: Ipv4Packet): Ipv4Packet? {
        val udp = packet.payload as? UdpDatagram ?: return null
        val mapping = byOutsidePort[udp.dstPort] ?: return null
        if (!mapping.static && (packet.src to udp.srcPort) !in mapping.peers) return null
        return packet.copy(dst = mapping.insideAddress, payload = udp.copy(dstPort = mapping.insidePort))
    }

    /** Forgets every dynamic mapping; port forwards stay. */
    fun reset() {
        byOutsidePort.clear()
        byInside.clear()
        nextPort = firstDynamicPort
        portForwards.forEach { rule ->
            val mapping = Mapping(rule.outsidePort, rule.insideAddress, rule.insidePort, static = true)
            byOutsidePort[rule.outsidePort] = mapping
            byInside[rule.insideAddress to rule.insidePort] = mapping
        }
    }

    private fun allocate(address: Ipv4Address, port: Int): Mapping {
        while (nextPort in byOutsidePort) nextPort++
        check(nextPort <= 65535) { "NAT port range exhausted" }
        return Mapping(nextPort++, address, port, static = false).also {
            byOutsidePort[it.outsidePort] = it
            byInside[address to port] = it
        }
    }

    fun state(): List<Map<String, Any?>> = byOutsidePort.values.map {
        mapOf(
            "outsidePort" to it.outsidePort,
            "inside" to "${it.insideAddress}:${it.insidePort}",
            "kind" to if (it.static) "PORT_FORWARD" else "DYNAMIC",
            "peers" to it.peers.map { (ip, port) -> "$ip:$port" },
        )
    }
}
