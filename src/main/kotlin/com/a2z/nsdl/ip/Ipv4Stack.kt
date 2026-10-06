package com.a2z.nsdl.ip

import com.a2z.nsdl.link.FramePort
import com.a2z.nsdl.model.DropReason
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.ArpOperation
import com.a2z.nsdl.net.ArpPacket
import com.a2z.nsdl.net.EthernetFrame
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import com.a2z.nsdl.net.Ipv4Packet
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.net.UdpDatagram
import com.a2z.nsdl.net.UdpPayload

data class ReceivedDatagram(val srcMac: MacAddress, val packet: Ipv4Packet, val datagram: UdpDatagram)

fun interface UdpHandler {
    fun onDatagram(datagram: ReceivedDatagram)
}

/** UDP send/receive as seen by protocols. */
interface UdpTransport {
    val interfaceId: ObjectId
    val hardwareAddress: MacAddress
    val config: Ipv4Config?
    val linkUp: Boolean
    fun addLinkListener(listener: (Boolean) -> Unit)
    /**
     * Sends a datagram. Broadcast destinations go to ff:ff:ff:ff:ff:ff. A unicast destination uses
     * [dstMac] when given; otherwise its next hop is resolved with ARP, queueing the datagram until
     * the reply arrives. Returns false when the datagram could be neither sent nor queued.
     */
    fun sendUdp(srcPort: Int, dst: Ipv4Address, dstPort: Int, payload: UdpPayload, dstMac: MacAddress? = null, ttl: Int = 64): Boolean
    fun bind(port: Int, handler: UdpHandler)
    fun unbind(port: Int)

    /**
     * Why traffic to [dst] may not be getting through, judged from this interface alone (link,
     * addressing, route, ARP), as a short human-readable reason; null when nothing local explains it.
     */
    fun explainUnreachable(dst: Ipv4Address): String? = null
}

/** Where a protocol applies (or clears) the interface's IP configuration. */
fun interface IpConfigurable {
    fun applyConfig(config: Ipv4Config?)
}

/**
 * Handed an inbound frame whose IPv4 destination is not this interface's own address. Lets a router
 * compose several [Ipv4Stack]s and forward between them instead of each one silently dropping traffic
 * meant for another interface. Absent (the default), such a frame is dropped as [DropReason.NO_LISTENER].
 */
fun interface Ipv4Forwarder {
    fun forward(frame: EthernetFrame, packet: Ipv4Packet)
}

/**
 * Offered a unicast UDP packet addressed to this interface's own address whose port has no local
 * listener, before it would be dropped. Returns true if it took the packet (e.g. NAT translating it
 * for an inside host). Lets a router claim traffic for its outside address without binding ports.
 */
fun interface Ipv4Interceptor {
    fun intercept(frame: EthernetFrame, packet: Ipv4Packet): Boolean
}

/**
 * Minimal per-interface IPv4 host stack: one address, UDP only, no fragmentation. Accepts packets
 * addressed to its address, the limited broadcast, or its subnet broadcast; anything else is handed
 * to [forwarder] when present, or dropped.
 *
 * ARP: unicast sends resolve the next hop (the destination on-link, else the configured router)
 * through an ARP cache. Unresolved packets wait in a small per-next-hop queue while a request is
 * outstanding; there are no timers, so a queue that overflows drops its oldest packet as
 * [DropReason.ARP_UNRESOLVED] and re-sends the request. The stack answers requests for its own
 * address and learns the requester's mapping. The cache and queues are operational state: they are
 * cleared when the link goes down or the configuration changes.
 */
class Ipv4Stack(
    private val port: FramePort,
    private val events: EventSink,
    private val interceptor: Ipv4Interceptor? = null,
    private val forwarder: Ipv4Forwarder? = null,
) : UdpTransport, IpConfigurable {
    private val listeners = mutableMapOf<Int, UdpHandler>()
    private val arpCache = linkedMapOf<Ipv4Address, MacAddress>()
    private val pending = linkedMapOf<Ipv4Address, MutableList<Ipv4Packet>>()

    override var config: Ipv4Config? = null
        private set
    override val interfaceId get() = port.id
    override val hardwareAddress get() = port.mac
    override val linkUp get() = port.linkUp
    override fun addLinkListener(listener: (Boolean) -> Unit) = port.addLinkListener(listener)

    /** The resolved neighbors, for inspection and tests. */
    val arpTable: Map<Ipv4Address, MacAddress> get() = arpCache.toMap()

    init {
        port.bindUpperLayer(::onFrame)
        port.addLinkListener { up -> if (!up) clearNeighbors() }
        port.contributeState {
            mapOf(
                "ipv4" to config?.toState(),
                "arp" to arpCache.map { (ip, mac) -> mapOf("address" to ip.toString(), "mac" to mac.toString()) },
            )
        }
    }

    override fun bind(port: Int, handler: UdpHandler) {
        check(port !in listeners) { "UDP port $port already bound on $interfaceId" }
        listeners[port] = handler
    }

    override fun unbind(port: Int) { listeners -= port }

    override fun applyConfig(config: Ipv4Config?) {
        if (config == this.config) return
        this.config = config
        clearNeighbors()
        events.emit(port.id, EventPayload.NetworkConfigChanged(config))
    }

    override fun sendUdp(srcPort: Int, dst: Ipv4Address, dstPort: Int, payload: UdpPayload, dstMac: MacAddress?, ttl: Int): Boolean {
        val packet = Ipv4Packet(config?.address ?: Ipv4Address.ANY, dst, UdpDatagram(srcPort, dstPort, payload), ttl = ttl)
        return sendPacket(packet, dstMac)
    }

    /**
     * Sends [packet] out this interface: to [dstMac] when given, to the broadcast MAC for a broadcast
     * destination, else to the ARP-resolved MAC of [nextHop] (default: the on-link destination, or the
     * configured router for an off-subnet one). A router forwarding a packet passes its route's next hop.
     */
    fun sendPacket(packet: Ipv4Packet, dstMac: MacAddress? = null, nextHop: Ipv4Address? = null): Boolean {
        if (dstMac != null) return port.send(EthernetFrame(port.mac, dstMac, packet))
        if (isBroadcast(packet.dst)) return port.send(EthernetFrame(port.mac, MacAddress.BROADCAST, packet))
        val c = config ?: return port.send(EthernetFrame(port.mac, MacAddress.BROADCAST, packet)) // no address to ARP from
        val hop = nextHop ?: if (packet.dst.inSubnet(c.address, c.subnetMask)) packet.dst else c.router
        if (hop == null) {
            events.emit(port.id, EventPayload.FrameDropped(EthernetFrame(port.mac, MacAddress.ZERO, packet), DropReason.NO_ROUTE))
            return false
        }
        arpCache[hop]?.let { return port.send(EthernetFrame(port.mac, it, packet)) }
        return enqueue(hop, packet, c)
    }

    override fun explainUnreachable(dst: Ipv4Address): String? {
        if (!port.linkUp) return "the network link is down"
        val c = config ?: return "this host has no IP address"
        if (isBroadcast(dst)) return null
        val hop = if (dst.inSubnet(c.address, c.subnetMask)) dst else c.router ?: return "no route to $dst"
        return if (hop in pending) "no ARP reply from $hop" + (if (hop == dst) "" else " (the router)") else null
    }

    private fun enqueue(hop: Ipv4Address, packet: Ipv4Packet, c: Ipv4Config): Boolean {
        val queue = pending.getOrPut(hop) { mutableListOf() }
        var overflowed = false
        if (queue.size >= MAX_PENDING_PER_HOP) {
            val evicted = queue.removeAt(0)
            events.emit(port.id, EventPayload.FrameDropped(EthernetFrame(port.mac, MacAddress.ZERO, evicted), DropReason.ARP_UNRESOLVED))
            overflowed = true
        }
        queue += packet
        if (queue.size > 1 && !overflowed) return true // a request for this hop is already outstanding

        val request = ArpPacket(ArpOperation.REQUEST, port.mac, c.address, MacAddress.ZERO, hop)
        if (!port.send(EthernetFrame(port.mac, MacAddress.BROADCAST, request))) {
            pending -= hop
            return false
        }
        return true
    }

    private fun onFrame(frame: EthernetFrame) {
        (frame.payload as? ArpPacket)?.let { onArp(it); return }
        val packet = frame.payload as? Ipv4Packet
        if (packet != null && !isForUs(packet.dst) && forwarder != null) {
            forwarder.forward(frame, packet)
            return
        }
        val datagram = packet?.payload as? UdpDatagram
        val handler = datagram?.let { listeners[it.dstPort] }
        if (packet != null && handler == null && packet.dst == config?.address && interceptor?.intercept(frame, packet) == true) return
        if (packet == null || datagram == null || handler == null || !isForUs(packet.dst)) {
            events.emit(port.id, EventPayload.FrameDropped(frame, DropReason.NO_LISTENER))
            return
        }
        events.emit(port.id, EventPayload.PacketAccepted(packet))
        handler.onDatagram(ReceivedDatagram(frame.src, packet, datagram))
    }

    /** Requests for someone else are ignored silently, as a real host does with broadcast ARP chatter. */
    private fun onArp(arp: ArpPacket) {
        val c = config ?: return
        val forUs = arp.targetIp == c.address
        if (!arp.senderIp.isUnspecified && (forUs || arp.senderIp in arpCache || arp.senderIp in pending)) {
            learn(arp.senderIp, arp.senderMac)
        }
        if (forUs && arp.operation == ArpOperation.REQUEST) {
            val reply = ArpPacket(ArpOperation.REPLY, port.mac, c.address, arp.senderMac, arp.senderIp)
            port.send(EthernetFrame(port.mac, arp.senderMac, reply))
        }
    }

    private fun learn(ip: Ipv4Address, mac: MacAddress) {
        val previous = arpCache.put(ip, mac)
        if (previous != mac) {
            events.emit(port.id, EventPayload.ProtocolStateChanged(PROTOCOL, if (previous == null) "UNRESOLVED" else "STALE", "RESOLVED", "$ip is-at $mac"))
        }
        pending.remove(ip)?.forEach { port.send(EthernetFrame(port.mac, mac, it)) }
    }

    private fun clearNeighbors() {
        arpCache.clear()
        pending.clear()
    }

    private fun isBroadcast(dst: Ipv4Address): Boolean {
        if (dst.isBroadcast) return true
        val c = config ?: return false
        return dst == subnetBroadcast(c)
    }

    private fun isForUs(dst: Ipv4Address): Boolean {
        if (dst.isBroadcast) return true
        val c = config ?: return false
        return dst == c.address || dst == subnetBroadcast(c)
    }

    private fun subnetBroadcast(c: Ipv4Config) = Ipv4Address(c.address.bits or c.subnetMask.bits.inv())

    companion object {
        const val PROTOCOL = "arp"
        private const val MAX_PENDING_PER_HOP = 8
    }
}

fun Ipv4Config.toState(): Map<String, Any?> = mapOf(
    "address" to address.toString(),
    "subnetMask" to subnetMask.toString(),
    "router" to router?.toString(),
    "source" to source.name,
    "leaseSeconds" to leaseSeconds,
    "server" to server?.toString(),
    "dnsServer" to dnsServer?.toString(),
)
