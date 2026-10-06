package com.a2z.nsdl.ip

import com.a2z.nsdl.link.FramePort
import com.a2z.nsdl.model.DecisionAction
import com.a2z.nsdl.model.DecisionParents
import com.a2z.nsdl.model.DecisionRecord
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.Inspectable
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.model.Responsibility
import com.a2z.nsdl.net.UdpDatagram
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import com.a2z.nsdl.net.Ipv4Packet

/**
 * A composable multi-interface IPv4 router: one [Ipv4Stack] per attached [FramePort], a shared
 * [RoutingTable] of directly-connected, static and default routes, and decision-logged forwarding
 * between them.
 *
 * Forwarding is driven entirely by [Ipv4Stack]'s [Ipv4Forwarder] hook: each interface's own stack
 * still handles traffic addressed to its own configuration (so a bound [com.a2z.nsdl.ip.UdpTransport],
 * such as a DHCP server, works exactly as on a single-interface host); anything else is handed here.
 * A forwarded packet leaves through the egress interface's stack, which resolves the route's next hop
 * (or, for a directly connected route, the destination itself) with ARP.
 *
 * With a [NatConfig], packets from the inside interface leaving through the outside one are
 * source-translated, and packets to the outside address are translated back (see [NatTable]); each
 * translation is logged as a [Responsibility.NAT] decision alongside the routing decision.
 *
 * Router has no lifecycle of its own: it has no per-generation timers, and forwarding naturally stops
 * when an interface is disabled (an [FramePort.send] on a disabled port already reports
 * [com.a2z.nsdl.model.DropReason.LINK_DOWN]). It composes alongside a device's interfaces, not as a
 * [com.a2z.nsdl.device.DeviceService] -- that would invert the ip -> device dependency direction.
 */
class Router(
    override val id: ObjectId,
    private val interfaces: List<FramePort>,
    private val events: EventSink,
    private val decisionCapacity: Int = 100,
    private val nat: NatConfig? = null,
) : Inspectable {
    init { require(decisionCapacity > 0) { "decision capacity must be positive" } }

    private val natTable = nat?.let { NatTable(it.portForwards) }

    private val stacksByInterface: Map<ObjectId, Ipv4Stack> = interfaces.associate { port ->
        val interceptor = if (nat != null && port.id == nat.outside) Ipv4Interceptor { _, packet -> inbound(port.id, packet) } else null
        port.id to Ipv4Stack(port, events, forwarder = { _, packet -> forward(port.id, packet) }, interceptor = interceptor)
    }

    init {
        if (nat != null) {
            interfaces.filter { it.id == nat.inside || it.id == nat.outside }.forEach { port ->
                port.addLinkListener { up -> if (!up) natTable?.reset() }
            }
        }
    }
    private val staticRoutes = mutableListOf<Route>()
    private val decisions = mutableListOf<DecisionRecord>()
    private var nextDecision = 1L

    /** The UDP transport for one attached interface, e.g. to bind a DHCP server on a LAN interface only. */
    fun transport(interfaceId: ObjectId): UdpTransport = stacksByInterface.getValue(interfaceId)

    fun configure(interfaceId: ObjectId, config: Ipv4Config?) {
        stacksByInterface.getValue(interfaceId).applyConfig(config)
    }

    fun addRoute(route: Route) {
        staticRoutes += route
    }

    /** Directly connected routes plus every added static/default route, for inspection and lookup. */
    fun routes(): List<Route> = connectedRoutes() + staticRoutes

    private fun connectedRoutes(): List<Route> = stacksByInterface.values.mapNotNull { stack ->
        val cfg = stack.config ?: return@mapNotNull null
        Route(
            destination = Ipv4Address(cfg.address.bits and cfg.subnetMask.bits),
            prefixLength = cfg.subnetMask.bits.countOneBits(),
            nextHop = null,
            interfaceId = stack.interfaceId,
            kind = RouteKind.CONNECTED,
        )
    }

    private fun lookup(dst: Ipv4Address): Route? = routes().filter { it.matches(dst) }.maxByOrNull { it.prefixLength }

    /** NAT inbound: translate a packet for the outside address back to its inside host, then route it. */
    private fun inbound(ingressId: ObjectId, packet: Ipv4Packet): Boolean {
        val table = natTable ?: return false
        val udp = packet.payload as? UdpDatagram ?: return false
        val translated = table.inbound(packet)
        val attributes = mapOf("original" to "${packet.dst}:${udp.dstPort}", "source" to "${packet.src}:${udp.srcPort}")
        if (translated == null) {
            record(DecisionAction.DROP, "no NAT mapping for this port and peer", ingressId, attributes, Responsibility.NAT)
            return false
        }
        val inside = translated.payload as UdpDatagram
        record(DecisionAction.TRANSLATE, "destination translated", ingressId, attributes + ("translated" to "${translated.dst}:${inside.dstPort}"), Responsibility.NAT)
        forward(ingressId, translated)
        return true
    }

    private fun forward(ingressId: ObjectId, packet: Ipv4Packet) {
        if (packet.ttl <= 1) {
            record(DecisionAction.DROP, "ttl expired", ingressId, mapOf("destination" to packet.dst.toString(), "ttl" to packet.ttl.toString()))
            return
        }
        val route = lookup(packet.dst)
        if (route == null) {
            record(DecisionAction.DROP, "no matching route", ingressId, mapOf("destination" to packet.dst.toString()))
            return
        }
        val egress = stacksByInterface.getValue(route.interfaceId)
        var forwardedPacket = packet.copy(ttl = packet.ttl - 1)
        if (nat != null && natTable != null && ingressId == nat.inside && route.interfaceId == nat.outside) {
            forwardedPacket = translateOutbound(ingressId, forwardedPacket, egress) ?: return
        }
        val attributes = mapOf(
            "destination" to packet.dst.toString(),
            "matchedPrefix" to "${route.destination}/${route.prefixLength}",
            "nextHop" to (route.nextHop?.toString() ?: "directly-connected"),
            "egressInterface" to route.interfaceId.value,
            "ttlBefore" to packet.ttl.toString(),
            "ttlAfter" to forwardedPacket.ttl.toString(),
        )
        val sent = egress.sendPacket(forwardedPacket, nextHop = route.nextHop ?: packet.dst)
        if (sent) {
            record(DecisionAction.FORWARD, "forwarded via ${route.kind.name.lowercase()} route", ingressId, attributes)
        } else {
            record(DecisionAction.DROP, "egress interface unavailable", ingressId, attributes)
        }
    }

    private fun translateOutbound(ingressId: ObjectId, packet: Ipv4Packet, egress: Ipv4Stack): Ipv4Packet? {
        val udp = packet.payload as? UdpDatagram ?: return packet
        val original = mapOf("original" to "${packet.src}:${udp.srcPort}", "destination" to "${packet.dst}:${udp.dstPort}")
        val outsideAddress = egress.config?.address
        if (outsideAddress == null) {
            record(DecisionAction.DROP, "outside interface has no address to translate to", ingressId, original, Responsibility.NAT)
            return null
        }
        val translation = natTable!!.outbound(packet, outsideAddress) ?: return packet
        val translated = "${translation.packet.src}:${(translation.packet.payload as UdpDatagram).srcPort}"
        record(
            DecisionAction.TRANSLATE,
            if (translation.created) "source translated (new mapping)" else "source translated",
            ingressId,
            original + ("translated" to translated),
            Responsibility.NAT,
        )
        return translation.packet
    }

    private fun record(
        action: DecisionAction,
        reason: String,
        ingressId: ObjectId,
        attributes: Map<String, String>,
        responsibility: Responsibility = Responsibility.ROUTING,
    ) {
        val r = DecisionRecord(
            id = "${id.value}:decision:${nextDecision++}",
            responsibility = responsibility,
            decision = action,
            reason = reason,
            parents = DecisionParents(),
            attributes = attributes + mapOf("ingressInterface" to ingressId.value),
        )
        decisions += r
        if (decisions.size > decisionCapacity) decisions.removeAt(0)
        events.emit(id, EventPayload.DecisionRecorded(r))
    }

    override fun snapshot() = ObjectSnapshot(
        id = id,
        type = "ip-router",
        kind = ObjectKind.PROTOCOL,
        state = mapOf(
            "routes" to routes().map { it.toState() },
            "decisions" to decisions.map { it.toState() },
            "nat" to nat?.let {
                mapOf(
                    "inside" to it.inside.value,
                    "outside" to it.outside.value,
                    "outsideAddress" to stacksByInterface.getValue(it.outside).config?.address?.toString(),
                    "mappings" to natTable?.state(),
                )
            },
        ),
        relations = mapOf("interfaces" to interfaces.map { it.id }),
    )
}
