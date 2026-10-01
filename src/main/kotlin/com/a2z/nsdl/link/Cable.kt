package com.a2z.nsdl.link

import com.a2z.nsdl.model.DropReason
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.Inspectable
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.model.TransitOutcome
import com.a2z.nsdl.net.EthernetFrame
import com.a2z.nsdl.net.Ipv4Packet
import com.a2z.nsdl.net.UdpDatagram
import com.a2z.nsdl.net.UdpPayload
import com.a2z.nsdl.net.decodeEnvelope
import com.a2z.nsdl.sim.Scheduler

/**
 * Passive point-to-point cable. It carries Ethernet frames between its two ends as an abstraction of
 * physical signaling and never looks above layer 2. It needs no power.
 *
 * The link is available when both ends are attached and enabled. A frame is delivered after the
 * profile's propagation delay; if the cable is disconnected (or re-connected) in the meantime, the
 * frame is dropped with [DropReason.DISCONNECTED_IN_FLIGHT].
 */
class Cable(
    override val id: ObjectId,
    val profile: LinkProfile,
    private val scheduler: Scheduler,
    private val events: EventSink,
    val type: String = "cat5-cable",
    private val historyCapacity: Int = DEFAULT_HISTORY_CAPACITY,
    /** Decodes an application payload (e.g. DHCP) for the packet-layer view; link stays protocol-agnostic. */
    private val describePayload: (UdpPayload) -> Map<String, Any?> = { emptyMap() },
) : Medium, Inspectable {
    init { require(historyCapacity > 0) { "history capacity must be positive" } }

    var endpoints: Pair<LinkEndpoint, LinkEndpoint>? = null
        private set
    private var epoch = 0L
    private val history = mutableListOf<EventPayload.PacketObserved>()
    private var nextTransit = 0L

    val isConnected get() = endpoints != null

    override val isLinkUp get() = endpoints?.let { (a, b) -> a.isEnabled && b.isEnabled } ?: false

    /** Reasons the connection is invalid, or null if it can be made. */
    fun connectionProblem(a: LinkEndpoint, b: LinkEndpoint): ConnectionRejection? = when {
        isConnected -> ConnectionRejection(ConnectionProblem.ALREADY_CONNECTED)
        a.id == b.id -> ConnectionRejection(ConnectionProblem.SELF_CONNECTION, a.id)
        a.media != profile.media || b.media != profile.media -> ConnectionRejection(ConnectionProblem.INCOMPATIBLE_MEDIA)
        a.isAttached -> ConnectionRejection(ConnectionProblem.ENDPOINT_OCCUPIED, a.id)
        b.isAttached -> ConnectionRejection(ConnectionProblem.ENDPOINT_OCCUPIED, b.id)
        else -> null
    }

    fun connect(a: LinkEndpoint, b: LinkEndpoint) {
        connectionProblem(a, b)?.let { throw IllegalStateException("cable $id: $it") }
        epoch++
        endpoints = a to b
        a.attached(this)
        b.attached(this)
        events.emit(id, EventPayload.Connected(a.id, b.id))
        a.refreshLinkState()
        b.refreshLinkState()
    }

    fun disconnect(): Boolean {
        val (a, b) = endpoints ?: return false
        epoch++
        endpoints = null
        a.detached()
        b.detached()
        events.emit(id, EventPayload.Disconnected(a.id, b.id))
        a.refreshLinkState()
        b.refreshLinkState()
        return true
    }

    override fun transmit(from: LinkEndpoint, frame: EthernetFrame) {
        val (a, b) = endpoints ?: return
        val to = if (from === a) b else a
        val sentInEpoch = epoch
        val sentAtMs = scheduler.now.millis
        val transitId = "${id.value}:t${++nextTransit}"
        scheduler.schedule(profile.propagationDelay) {
            if (epoch != sentInEpoch) {
                events.emit(id, EventPayload.FrameDropped(frame, DropReason.DISCONNECTED_IN_FLIGHT))
                recordTransit(transitId, sentAtMs, from.id, to.id, frame, TransitOutcome.DROPPED, DropReason.DISCONNECTED_IN_FLIGHT)
            } else {
                to.receive(frame)
                recordTransit(transitId, sentAtMs, from.id, to.id, frame, TransitOutcome.DELIVERED)
            }
        }
    }

    private fun recordTransit(
        transitId: String,
        sentAtMs: Long,
        from: ObjectId,
        to: ObjectId,
        frame: EthernetFrame,
        outcome: TransitOutcome,
        dropReason: DropReason? = null,
    ) {
        val observed = EventPayload.PacketObserved(transitId, sentAtMs, from, to, frame, outcome, dropReason)
        history += observed
        if (history.size > historyCapacity) history.removeAt(0)
        events.emit(id, observed)
    }

    private fun frameView(r: EventPayload.PacketObserved): Map<String, Any?> = mapOf(
        "id" to r.transitId,
        "sentAtMs" to r.sentAtMs,
        "from" to r.from.value,
        "to" to r.to.value,
        "sourceMac" to r.frame.src.toString(),
        "destMac" to r.frame.dst.toString(),
        "etherType" to "0x" + r.frame.etherType.toString(16).padStart(4, '0'),
        "outcome" to r.outcome.name,
        "dropReason" to r.dropReason?.name,
    )

    /** Null when the frame carries no IPv4 packet; otherwise the same [r.transitId] as its frame-view entry. */
    private fun packetView(r: EventPayload.PacketObserved): Map<String, Any?>? {
        val packet = r.frame.payload as? Ipv4Packet ?: return null
        val udp = packet.payload as? UdpDatagram
        return mapOf(
            "id" to r.transitId,
            "sentAtMs" to r.sentAtMs,
            "from" to r.from.value,
            "to" to r.to.value,
            "outcome" to r.outcome.name,
            "dropReason" to r.dropReason?.name,
        ) + r.frame.decodeEnvelope() + (udp?.let { describePayload(it.payload) } ?: emptyMap())
    }

    override fun endpointStateChanged() {
        endpoints?.let { (a, b) -> a.refreshLinkState(); b.refreshLinkState() }
    }

    override fun snapshot() = ObjectSnapshot(
        id, type, ObjectKind.CABLE,
        state = mapOf(
            "profile" to profile.name,
            "media" to profile.media.name,
            "nominalMbps" to profile.nominalMbps,
            "propagationDelayMs" to profile.propagationDelay.inWholeMilliseconds,
            "connected" to isConnected,
            "linkUp" to isLinkUp,
            "frames" to history.map { frameView(it) },
            "packets" to history.mapNotNull { packetView(it) },
        ),
        relations = mapOf("endpoints" to (endpoints?.let { listOf(it.first.id, it.second.id) } ?: emptyList())),
    )

    companion object {
        const val DEFAULT_HISTORY_CAPACITY = 200
    }
}
