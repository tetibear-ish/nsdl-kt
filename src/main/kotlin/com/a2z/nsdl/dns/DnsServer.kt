package com.a2z.nsdl.dns

import com.a2z.nsdl.device.DeviceService
import com.a2z.nsdl.ip.ReceivedDatagram
import com.a2z.nsdl.ip.UdpTransport
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.sim.WorkScope

/**
 * Authoritative name server for one local network. It answers A queries from two kinds of record:
 *
 * - static records ([addStatic]), part of the device's configuration, e.g. its own name;
 * - dynamic records ([register]/[unregister]), fed by the DHCP server as named leases come and go.
 *
 * Dynamic records are volatile like the leases they mirror: cleared when the hosting device stops.
 * Static records survive a power cycle. A dynamic record never overrides a static one.
 */
class DnsServer(
    override val id: ObjectId,
    private val transport: UdpTransport,
    private val events: EventSink,
) : DeviceService {
    private val staticRecords = linkedMapOf<String, Ipv4Address>()
    private val dynamicRecords = linkedMapOf<String, Ipv4Address>()
    private var active = false

    init {
        transport.bind(DnsProtocol.SERVER_PORT) { onDatagram(it) }
    }

    fun addStatic(name: String, address: Ipv4Address) {
        staticRecords[DnsProtocol.normalize(name)] = address
    }

    fun register(name: String, address: Ipv4Address) {
        val key = DnsProtocol.normalize(name)
        if (key.isEmpty() || key in staticRecords || dynamicRecords[key] == address) return
        val previous = dynamicRecords.put(key, address)
        events.emit(id, EventPayload.ProtocolStateChanged(PROTOCOL, if (previous == null) "NONE" else "REGISTERED", "REGISTERED", "$key A $address"))
    }

    fun unregister(name: String, address: Ipv4Address) {
        val key = DnsProtocol.normalize(name)
        if (dynamicRecords[key] != address) return
        dynamicRecords -= key
        events.emit(id, EventPayload.ProtocolStateChanged(PROTOCOL, "REGISTERED", "NONE", "$key A $address"))
    }

    fun lookup(name: String): Ipv4Address? = DnsProtocol.normalize(name).let { staticRecords[it] ?: dynamicRecords[it] }

    override fun start(scope: WorkScope) { active = true }

    override fun stop() {
        active = false
        dynamicRecords.clear()
    }

    private fun onDatagram(d: ReceivedDatagram) {
        val query = d.datagram.payload as? DnsMessage.Query ?: return
        if (!active) return
        val address = lookup(query.name)
        val answer = DnsMessage.Answer(
            query.queryId,
            DnsProtocol.normalize(query.name),
            if (address == null) DnsStatus.NXDOMAIN else DnsStatus.NOERROR,
            address,
        )
        transport.sendUdp(DnsProtocol.SERVER_PORT, d.packet.src, d.datagram.srcPort, answer, d.srcMac)
    }

    override fun snapshot() = ObjectSnapshot(
        id, PROTOCOL, ObjectKind.PROTOCOL,
        state = mapOf(
            "active" to active,
            "records" to staticRecords.map { (name, address) -> mapOf("name" to name, "address" to address.toString(), "source" to "STATIC") } +
                dynamicRecords.map { (name, address) -> mapOf("name" to name, "address" to address.toString(), "source" to "DHCP") },
        ),
        relations = mapOf("interface" to listOf(transport.interfaceId)),
    )

    companion object {
        const val PROTOCOL = "dns-server"
    }
}
