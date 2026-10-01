package com.a2z.nsdl.ip

import com.a2z.nsdl.link.FramePort
import com.a2z.nsdl.model.DropReason
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.ObjectId
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
    /** Sends a datagram. Without ARP, unicast needs an explicit [dstMac]; broadcast uses ff:ff:ff:ff:ff:ff. */
    fun sendUdp(srcPort: Int, dst: Ipv4Address, dstPort: Int, payload: UdpPayload, dstMac: MacAddress = MacAddress.BROADCAST, ttl: Int = 64): Boolean
    fun bind(port: Int, handler: UdpHandler)
    fun unbind(port: Int)
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
 * Minimal per-interface IPv4 host stack: one address, UDP only, no ARP, no fragmentation. Accepts
 * packets addressed to its address, the limited broadcast, or its subnet broadcast; anything else is
 * handed to [forwarder] when present, or dropped.
 */
class Ipv4Stack(
    private val port: FramePort,
    private val events: EventSink,
    private val forwarder: Ipv4Forwarder? = null,
) : UdpTransport, IpConfigurable {
    private val listeners = mutableMapOf<Int, UdpHandler>()

    override var config: Ipv4Config? = null
        private set
    override val interfaceId get() = port.id
    override val hardwareAddress get() = port.mac
    override val linkUp get() = port.linkUp
    override fun addLinkListener(listener: (Boolean) -> Unit) = port.addLinkListener(listener)

    init {
        port.bindUpperLayer(::onFrame)
        port.contributeState { mapOf("ipv4" to config?.toState()) }
    }

    override fun bind(port: Int, handler: UdpHandler) {
        check(port !in listeners) { "UDP port $port already bound on $interfaceId" }
        listeners[port] = handler
    }

    override fun unbind(port: Int) { listeners -= port }

    override fun applyConfig(config: Ipv4Config?) {
        if (config == this.config) return
        this.config = config
        events.emit(port.id, EventPayload.NetworkConfigChanged(config))
    }

    override fun sendUdp(srcPort: Int, dst: Ipv4Address, dstPort: Int, payload: UdpPayload, dstMac: MacAddress, ttl: Int): Boolean {
        val packet = Ipv4Packet(config?.address ?: Ipv4Address.ANY, dst, UdpDatagram(srcPort, dstPort, payload), ttl = ttl)
        return port.send(EthernetFrame(port.mac, dstMac, packet))
    }

    private fun onFrame(frame: EthernetFrame) {
        val packet = frame.payload as? Ipv4Packet
        if (packet != null && !isForUs(packet.dst) && forwarder != null) {
            forwarder.forward(frame, packet)
            return
        }
        val datagram = packet?.payload as? UdpDatagram
        val handler = datagram?.let { listeners[it.dstPort] }
        if (packet == null || datagram == null || handler == null || !isForUs(packet.dst)) {
            events.emit(port.id, EventPayload.FrameDropped(frame, DropReason.NO_LISTENER))
            return
        }
        events.emit(port.id, EventPayload.PacketAccepted(packet))
        handler.onDatagram(ReceivedDatagram(frame.src, packet, datagram))
    }

    private fun isForUs(dst: Ipv4Address): Boolean {
        if (dst.isBroadcast) return true
        val c = config ?: return false
        return dst == c.address || dst == Ipv4Address(c.address.bits or c.subnetMask.bits.inv())
    }
}

fun Ipv4Config.toState(): Map<String, Any?> = mapOf(
    "address" to address.toString(),
    "subnetMask" to subnetMask.toString(),
    "router" to router?.toString(),
    "source" to source.name,
    "leaseSeconds" to leaseSeconds,
    "server" to server?.toString(),
)
