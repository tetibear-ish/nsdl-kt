package com.a2z.nsdl.dhcp

import com.a2z.nsdl.device.DeviceService
import com.a2z.nsdl.ip.IpConfigurable
import com.a2z.nsdl.ip.ReceivedDatagram
import com.a2z.nsdl.ip.UdpTransport
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.net.ConfigSource
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import com.a2z.nsdl.sim.Cancellable
import com.a2z.nsdl.sim.WorkScope
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class DhcpClientState { STOPPED, INIT, SELECTING, REQUESTING, BOUND }

/** Retransmission policy (RFC 2131 4.1: 4s doubling to 64s, randomized by +/-1s). */
data class DhcpClientTimers(
    val initialTimeout: Duration = 4.seconds,
    val maxTimeout: Duration = 64.seconds,
    val jitter: Duration = 1.seconds,
    val maxRequestAttempts: Int = 4,
    val restartDelay: Duration = 1.seconds,
)

/**
 * DHCPv4 client acquisition subset: INIT -> SELECTING -> REQUESTING -> BOUND.
 *
 * - Discovery starts when the client is started and the link is up (or as soon as the link comes up).
 * - The first acceptable OFFER selects the server; its REQUEST is broadcast with options 50 and 54.
 * - Replies are accepted only if op=REPLY, xid matches the current transaction and chaddr is ours;
 *   ACK/NAK must also come from the selected server. Configuration is applied only on ACK.
 * - NAK, or exhausting REQUEST retries, returns to INIT and restarts with a new xid after [DhcpClientTimers.restartDelay].
 * - Each start draws a fresh xid, so replies addressed to an earlier run never match.
 * - Stop clears the acquired configuration (leases are not remembered across power cycles).
 */
class DhcpClient(
    override val id: ObjectId,
    private val transport: UdpTransport,
    private val configurator: IpConfigurable,
    private val random: Random,
    private val events: EventSink,
    private val timers: DhcpClientTimers = DhcpClientTimers(),
) : DeviceService {
    var state = DhcpClientState.STOPPED
        private set
    private var scope: WorkScope? = null
    private var timer: Cancellable? = null
    private var xid = 0
    private var attempts = 0
    private var selectedServer: Ipv4Address? = null
    private var offeredIp: Ipv4Address? = null
    private var ignoredReplies = 0L

    init {
        transport.bind(DhcpMessage.CLIENT_PORT) { onDatagram(it) }
        transport.addLinkListener { up -> onLinkChanged(up) }
    }

    override fun start(scope: WorkScope) {
        if (state != DhcpClientState.STOPPED) return
        this.scope = scope
        transition(DhcpClientState.INIT)
        if (transport.linkUp) beginSelecting()
    }

    override fun stop() {
        cancelTimer()
        scope = null
        if (state == DhcpClientState.STOPPED) return
        configurator.applyConfig(null)
        selectedServer = null
        offeredIp = null
        transition(DhcpClientState.STOPPED)
    }

    private fun onLinkChanged(up: Boolean) {
        if (!up) return
        when (state) {
            DhcpClientState.INIT, DhcpClientState.SELECTING, DhcpClientState.REQUESTING -> { cancelTimer(); beginSelecting() }
            DhcpClientState.STOPPED, DhcpClientState.BOUND -> Unit
        }
    }

    private fun beginSelecting() {
        xid = random.nextInt()
        attempts = 0
        selectedServer = null
        offeredIp = null
        transition(DhcpClientState.SELECTING, "xid=0x%08x".format(xid))
        sendDiscover()
    }

    private fun sendDiscover() {
        send(DhcpMessage(BootOp.REQUEST, DhcpMessageType.DISCOVER, xid, transport.hardwareAddress))
        arm(backoff(attempts)) { attempts++; sendDiscover() }
    }

    private fun sendRequest() {
        send(
            DhcpMessage(
                BootOp.REQUEST, DhcpMessageType.REQUEST, xid, transport.hardwareAddress,
                requestedIp = offeredIp, serverId = selectedServer,
            ),
        )
        arm(backoff(attempts)) {
            attempts++
            if (attempts >= timers.maxRequestAttempts) restart("no ACK after $attempts REQUESTs") else sendRequest()
        }
    }

    private fun restart(reason: String) {
        cancelTimer()
        selectedServer = null
        offeredIp = null
        transition(DhcpClientState.INIT, reason)
        arm(timers.restartDelay) { if (transport.linkUp) beginSelecting() }
    }

    private fun onDatagram(d: ReceivedDatagram) {
        val msg = d.datagram.payload as? DhcpMessage ?: return
        if (state == DhcpClientState.STOPPED) return
        if (msg.op != BootOp.REPLY || msg.xid != xid || msg.chaddr != transport.hardwareAddress) {
            ignoredReplies++
            return
        }
        when {
            state == DhcpClientState.SELECTING && msg.type == DhcpMessageType.OFFER &&
                msg.serverId != null && !msg.yiaddr.isUnspecified -> {
                cancelTimer()
                selectedServer = msg.serverId
                offeredIp = msg.yiaddr
                attempts = 0
                transition(DhcpClientState.REQUESTING, "server=${msg.serverId} offered=${msg.yiaddr}")
                sendRequest()
            }
            state == DhcpClientState.REQUESTING && msg.serverId == selectedServer && msg.type == DhcpMessageType.ACK &&
                msg.yiaddr == offeredIp -> bind(msg)
            state == DhcpClientState.REQUESTING && msg.serverId == selectedServer && msg.type == DhcpMessageType.NAK ->
                restart("NAK from ${msg.serverId}")
            else -> ignoredReplies++
        }
    }

    private fun bind(ack: DhcpMessage) {
        cancelTimer()
        configurator.applyConfig(
            Ipv4Config(
                address = ack.yiaddr,
                subnetMask = ack.subnetMask ?: DEFAULT_MASK,
                router = ack.router,
                source = ConfigSource.DHCP,
                leaseSeconds = ack.leaseSeconds,
                server = ack.serverId,
            ),
        )
        transition(DhcpClientState.BOUND, "address=${ack.yiaddr} lease=${ack.leaseSeconds}s")
    }

    private fun send(msg: DhcpMessage) {
        transport.sendUdp(DhcpMessage.CLIENT_PORT, Ipv4Address.BROADCAST, DhcpMessage.SERVER_PORT, msg)
    }

    private fun arm(delay: Duration, action: () -> Unit) {
        cancelTimer()
        timer = scope?.schedule(delay) { timer = null; action() }
    }

    private fun cancelTimer() { timer?.cancel(); timer = null }

    private fun backoff(attempt: Int): Duration {
        val base = minOf(timers.initialTimeout * (1 shl minOf(attempt, 10)), timers.maxTimeout)
        val j = timers.jitter.inWholeMilliseconds
        return if (j == 0L) base else base + random.nextLong(-j, j + 1).milliseconds
    }

    private fun transition(to: DhcpClientState, detail: String = "") {
        val from = state
        state = to
        events.emit(id, EventPayload.ProtocolStateChanged(PROTOCOL, from.name, to.name, detail))
    }

    override fun snapshot() = ObjectSnapshot(
        id, PROTOCOL, ObjectKind.PROTOCOL,
        state = mapOf(
            "state" to state.name,
            "xid" to if (state == DhcpClientState.STOPPED) null else "0x%08x".format(xid),
            "attempts" to attempts,
            "selectedServer" to selectedServer?.toString(),
            "offeredAddress" to offeredIp?.toString(),
            "ignoredReplies" to ignoredReplies,
        ),
        relations = mapOf("interface" to listOf(transport.interfaceId)),
    )

    companion object {
        const val PROTOCOL = "dhcp-client"
        private val DEFAULT_MASK = Ipv4Address.parse("255.255.255.0")
    }
}
