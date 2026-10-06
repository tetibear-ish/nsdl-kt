package com.a2z.nsdl.print

import com.a2z.nsdl.ip.IpConfigurable
import com.a2z.nsdl.ip.ReceivedDatagram
import com.a2z.nsdl.ip.UdpHandler
import com.a2z.nsdl.ip.UdpTransport
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.ConfigSource
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import com.a2z.nsdl.net.Ipv4Packet
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.net.UdpDatagram
import com.a2z.nsdl.net.UdpPayload

class RecordingUdpTransport : UdpTransport, IpConfigurable {
    data class Sent(val srcPort: Int, val dst: Ipv4Address, val dstPort: Int, val payload: UdpPayload, val dstMac: MacAddress?)

    override val interfaceId = ObjectId("test.eth0")
    override val hardwareAddress = MacAddress.local(99)
    override var config: Ipv4Config? = Ipv4Config(
        Ipv4Address.parse("192.168.1.20"),
        Ipv4Address.parse("255.255.255.0"),
        source = ConfigSource.STATIC,
    )
    override val linkUp = true
    val sent = mutableListOf<Sent>()
    private val handlers = mutableMapOf<Int, UdpHandler>()

    override fun addLinkListener(listener: (Boolean) -> Unit) = Unit
    override fun sendUdp(srcPort: Int, dst: Ipv4Address, dstPort: Int, payload: UdpPayload, dstMac: MacAddress?, ttl: Int): Boolean {
        sent += Sent(srcPort, dst, dstPort, payload, dstMac)
        return true
    }
    override fun bind(port: Int, handler: UdpHandler) { handlers[port] = handler }
    override fun unbind(port: Int) { handlers -= port }
    override fun applyConfig(config: Ipv4Config?) { this.config = config }

    fun deliver(payload: UdpPayload, srcPort: Int = PrintProtocol.CLIENT_PORT) {
        val src = Ipv4Address.parse("192.168.1.10")
        val datagram = UdpDatagram(srcPort, PrintProtocol.SERVER_PORT, payload)
        handlers.getValue(PrintProtocol.SERVER_PORT).onDatagram(
            ReceivedDatagram(MacAddress.local(1), Ipv4Packet(src, config!!.address, datagram), datagram),
        )
    }
}
