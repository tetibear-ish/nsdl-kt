package com.a2z.nsdl.dhcp

import com.a2z.nsdl.device.DeviceService
import com.a2z.nsdl.ip.ReceivedDatagram
import com.a2z.nsdl.ip.UdpTransport
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.sim.WorkScope

data class DhcpPool(
    val start: Ipv4Address,
    val end: Ipv4Address,
    val subnetMask: Ipv4Address,
    val router: Ipv4Address?,
    val leaseSeconds: Long,
) {
    val addresses: List<Ipv4Address> get() = (start.bits..end.bits).map(::Ipv4Address)
}

/**
 * Minimal DHCPv4 server hosted behind its own UDP transport. It only sees what arrives on its interface.
 *
 * - DISCOVER: offer the client's existing address or the lowest free pool address (silent if exhausted).
 * - REQUEST naming this server: ACK if the requested address is the one offered/leased to that chaddr, else NAK.
 * - REQUEST naming another server: withdraw the pending offer.
 * - REQUEST without a server identifier (INIT-REBOOT/RENEWING), DECLINE, RELEASE, INFORM: ignored (deferred).
 * Offers and leases are volatile: cleared when the hosting device stops.
 */
class DhcpServer(
    override val id: ObjectId,
    private val transport: UdpTransport,
    val pool: DhcpPool,
    private val events: EventSink,
) : DeviceService {
    private enum class BindingState { OFFERED, BOUND }
    private data class Binding(val address: Ipv4Address, val state: BindingState)

    private val bindings = linkedMapOf<MacAddress, Binding>()
    private var active = false

    init {
        transport.bind(DhcpMessage.SERVER_PORT) { onDatagram(it) }
    }

    override fun start(scope: WorkScope) { active = true }

    override fun stop() {
        active = false
        bindings.clear()
    }

    private fun onDatagram(d: ReceivedDatagram) {
        val msg = d.datagram.payload as? DhcpMessage ?: return
        val self = transport.config?.address ?: return
        if (!active || msg.op != BootOp.REQUEST) return
        when (msg.type) {
            DhcpMessageType.DISCOVER -> offer(msg, self)
            DhcpMessageType.REQUEST -> when (msg.serverId) {
                null -> Unit
                self -> request(msg, self)
                else -> withdraw(msg.chaddr)
            }
            else -> Unit
        }
    }

    private fun offer(msg: DhcpMessage, self: Ipv4Address) {
        val existing = bindings[msg.chaddr]
        val address = existing?.address ?: freeAddress() ?: return
        if (existing == null) setBinding(msg.chaddr, Binding(address, BindingState.OFFERED))
        reply(msg, DhcpMessageType.OFFER, self, address)
    }

    private fun request(msg: DhcpMessage, self: Ipv4Address) {
        val binding = bindings[msg.chaddr]
        if (binding != null && msg.requestedIp == binding.address) {
            setBinding(msg.chaddr, binding.copy(state = BindingState.BOUND))
            reply(msg, DhcpMessageType.ACK, self, binding.address)
        } else {
            reply(msg, DhcpMessageType.NAK, self, Ipv4Address.ANY)
        }
    }

    private fun withdraw(mac: MacAddress) {
        val binding = bindings[mac] ?: return
        if (binding.state == BindingState.OFFERED) {
            bindings -= mac
            events.emit(id, EventPayload.ProtocolStateChanged(PROTOCOL, "OFFERED", "NONE", "client=$mac chose another server"))
        }
    }

    private fun freeAddress(): Ipv4Address? {
        val used = bindings.values.map { it.address }.toSet()
        return pool.addresses.firstOrNull { it !in used }
    }

    private fun setBinding(mac: MacAddress, binding: Binding) {
        val from = bindings[mac]?.state?.name ?: "NONE"
        bindings[mac] = binding
        events.emit(id, EventPayload.ProtocolStateChanged(PROTOCOL, from, binding.state.name, "client=$mac address=${binding.address}"))
    }

    private fun reply(to: DhcpMessage, type: DhcpMessageType, self: Ipv4Address, address: Ipv4Address) {
        val positive = type != DhcpMessageType.NAK
        val msg = DhcpMessage(
            op = BootOp.REPLY, type = type, xid = to.xid, chaddr = to.chaddr,
            yiaddr = address, broadcast = to.broadcast, serverId = self,
            leaseSeconds = pool.leaseSeconds.takeIf { positive },
            subnetMask = pool.subnetMask.takeIf { positive },
            router = pool.router.takeIf { positive },
        )
        // A NAK is always broadcast (RFC 2131 4.3.2); otherwise honor the client's BROADCAST flag.
        val (dstIp, dstMac) = if (to.broadcast || !positive) Ipv4Address.BROADCAST to MacAddress.BROADCAST else address to to.chaddr
        transport.sendUdp(DhcpMessage.SERVER_PORT, dstIp, DhcpMessage.CLIENT_PORT, msg, dstMac)
    }

    override fun snapshot() = ObjectSnapshot(
        id, PROTOCOL, ObjectKind.PROTOCOL,
        state = mapOf(
            "active" to active,
            "serverIdentifier" to transport.config?.address?.toString(),
            "pool" to mapOf(
                "start" to pool.start.toString(), "end" to pool.end.toString(),
                "subnetMask" to pool.subnetMask.toString(), "router" to pool.router?.toString(),
                "leaseSeconds" to pool.leaseSeconds,
            ),
            "leases" to bindings.map { (mac, b) -> mapOf("mac" to mac.toString(), "address" to b.address.toString(), "state" to b.state.name) },
        ),
        relations = mapOf("interface" to listOf(transport.interfaceId)),
    )

    companion object {
        const val PROTOCOL = "dhcp-server"
    }
}
