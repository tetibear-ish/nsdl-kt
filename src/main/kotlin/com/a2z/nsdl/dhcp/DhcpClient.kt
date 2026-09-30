package com.a2z.nsdl.dhcp

import com.a2z.nsdl.device.DeviceService
import com.a2z.nsdl.ip.IpConfigurable
import com.a2z.nsdl.ip.ReceivedDatagram
import com.a2z.nsdl.ip.UdpTransport
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.hex
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

enum class DhcpClientState { STOPPED, INIT, SELECTING, REQUESTING, BOUND, RENEWING, REBINDING }

/** Retransmission policy (RFC 2131 4.1: 4s doubling to 64s, randomized by +/-1s). */
data class DhcpClientTimers(
    val initialTimeout: Duration = 4.seconds,
    val maxTimeout: Duration = 64.seconds,
    val jitter: Duration = 1.seconds,
    val maxRequestAttempts: Int = 4,
    val restartDelay: Duration = 1.seconds,
)

/**
 * DHCPv4 client acquisition and lease lifecycle: INIT -> SELECTING -> REQUESTING -> BOUND, then
 * BOUND -> RENEWING (T1, 50% of the lease) -> REBINDING (T2, 87.5%) -> INIT (100%, expiry).
 *
 * - Discovery starts when the client is started and the link is up (or as soon as the link comes up).
 * - The first acceptable OFFER selects the server; its REQUEST is broadcast with options 50 and 54.
 * - Replies are accepted only if op=REPLY, xid matches the current transaction and chaddr is ours;
 *   ACK/NAK must also come from the selected server. Configuration is applied only on ACK.
 * - NAK, or exhausting REQUEST retries, returns to INIT and restarts with a new xid after [DhcpClientTimers.restartDelay].
 * - At T1 the client unicasts renewal REQUESTs to its own server, retaining the address. At T2, with
 *   no ACK yet, it switches to broadcast rebinding REQUESTs any server may answer. A NAK during either
 *   clears configuration and restarts discovery immediately (no restartDelay: the address is already gone).
 *   Lease expiry at 100% does the same, but only once T2's retries never landed an ACK.
 * - T1/T2/expiry are absolute deadlines scheduled once at bind time (and rescheduled on every fresh
 *   ACK); they fire regardless of the link, so a disconnected lease's timers keep running -- reconnecting
 *   before expiry resumes renewal/rebinding immediately, reconnecting after starts a fresh DISCOVER.
 * - Each start (or T1/T2) draws a fresh xid, so replies addressed to an earlier run never match.
 * - Stop clears the acquired configuration and every lease timer (leases are not remembered across power cycles).
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
    private var t1Timer: Cancellable? = null
    private var t2Timer: Cancellable? = null
    private var expiryTimer: Cancellable? = null
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
        cancelLeaseTimers()
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
            DhcpClientState.RENEWING -> { cancelTimer(); attempts = 0; sendRenewal() }
            DhcpClientState.REBINDING -> { cancelTimer(); attempts = 0; sendRebind() }
            DhcpClientState.STOPPED, DhcpClientState.BOUND -> Unit
        }
    }

    private fun beginSelecting() {
        xid = random.nextInt()
        attempts = 0
        selectedServer = null
        offeredIp = null
        transition(DhcpClientState.SELECTING, "xid=0x${hex(xid.toLong(), 8)}")
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

    /** T1: unicast to the known server, since the client already has a working address to receive a reply on. */
    private fun sendRenewal() {
        val server = selectedServer ?: return
        send(
            DhcpMessage(
                BootOp.REQUEST, DhcpMessageType.REQUEST, xid, transport.hardwareAddress,
                requestedIp = offeredIp, serverId = server, broadcast = false,
            ),
            dst = server,
        )
        arm(backoff(attempts)) { attempts++; sendRenewal() }
    }

    /** T2: broadcast, since renewal never landed an ACK and any server that knows this lease may answer. */
    private fun sendRebind() {
        send(
            DhcpMessage(
                BootOp.REQUEST, DhcpMessageType.REQUEST, xid, transport.hardwareAddress,
                requestedIp = offeredIp, serverId = selectedServer, broadcast = true,
            ),
        )
        arm(backoff(attempts)) { attempts++; sendRebind() }
    }

    private fun restart(reason: String) {
        cancelTimer()
        selectedServer = null
        offeredIp = null
        transition(DhcpClientState.INIT, reason)
        arm(timers.restartDelay) { if (transport.linkUp) beginSelecting() }
    }

    /** NAK (or expiry) during an active lease: the address is already gone, so there is nothing to wait for. */
    private fun restartImmediately(reason: String) {
        cancelTimer()
        cancelLeaseTimers()
        configurator.applyConfig(null)
        selectedServer = null
        offeredIp = null
        transition(DhcpClientState.INIT, reason)
        if (transport.linkUp) beginSelecting()
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
            (state == DhcpClientState.REQUESTING || state == DhcpClientState.RENEWING || state == DhcpClientState.REBINDING) &&
                msg.serverId == selectedServer && msg.type == DhcpMessageType.ACK && msg.yiaddr == offeredIp -> bind(msg)
            state == DhcpClientState.REQUESTING && msg.serverId == selectedServer && msg.type == DhcpMessageType.NAK ->
                restart("NAK from ${msg.serverId}")
            (state == DhcpClientState.RENEWING || state == DhcpClientState.REBINDING) &&
                msg.serverId == selectedServer && msg.type == DhcpMessageType.NAK -> restartImmediately("NAK from ${msg.serverId} during $state")
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
        offeredIp = ack.yiaddr
        transition(DhcpClientState.BOUND, "address=${ack.yiaddr} lease=${ack.leaseSeconds}s")
        scheduleLeaseTimers(ack.leaseSeconds)
    }

    private fun scheduleLeaseTimers(leaseSeconds: Long?) {
        cancelLeaseTimers()
        if (leaseSeconds == null) return
        val totalMs = leaseSeconds * 1000L
        t1Timer = scope?.schedule((totalMs / 2).milliseconds) { t1Timer = null; onT1() }
        t2Timer = scope?.schedule((totalMs * 7 / 8).milliseconds) { t2Timer = null; onT2() }
        expiryTimer = scope?.schedule(totalMs.milliseconds) { expiryTimer = null; onExpiry() }
    }

    private fun cancelLeaseTimers() {
        t1Timer?.cancel(); t1Timer = null
        t2Timer?.cancel(); t2Timer = null
        expiryTimer?.cancel(); expiryTimer = null
    }

    private fun onT1() {
        if (state != DhcpClientState.BOUND) return
        xid = random.nextInt()
        attempts = 0
        transition(DhcpClientState.RENEWING, "xid=0x${hex(xid.toLong(), 8)}")
        sendRenewal()
    }

    private fun onT2() {
        if (state != DhcpClientState.RENEWING) return
        cancelTimer()
        xid = random.nextInt()
        attempts = 0
        transition(DhcpClientState.REBINDING, "xid=0x${hex(xid.toLong(), 8)}")
        sendRebind()
    }

    private fun onExpiry() {
        restartImmediately("lease expired")
    }

    private fun send(msg: DhcpMessage, dst: Ipv4Address = Ipv4Address.BROADCAST) {
        transport.sendUdp(DhcpMessage.CLIENT_PORT, dst, DhcpMessage.SERVER_PORT, msg)
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
            "xid" to if (state == DhcpClientState.STOPPED) null else "0x${hex(xid.toLong(), 8)}",
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
