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
import com.a2z.nsdl.sim.Cancellable
import com.a2z.nsdl.sim.WorkScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The outcome of one [DnsResolver.resolve]: an [address], or a human-readable [error]. [timedOut] marks
 * an error where the server never answered; [error] then says why, as far as this host can tell.
 */
data class Resolution(val name: String, val address: Ipv4Address?, val error: String? = null, val timedOut: Boolean = false)

/**
 * Stub resolver: asks the name server learned from the interface configuration (DHCP option 6).
 *
 * - A dotted-quad name resolves to itself without a query.
 * - Positive answers are cached for the rest of the run; NXDOMAIN is not cached.
 * - Concurrent lookups of the same name share one outstanding query.
 * - An unanswered query is retried every [retryInterval], up to [attempts] sends, then times out.
 * - A query that cannot even be sent (e.g. the link is down) fails at once.
 * The cache and outstanding queries are cleared when the host stops.
 */
class DnsResolver(
    override val id: ObjectId,
    private val transport: UdpTransport,
    private val events: EventSink,
    private val retryInterval: Duration = 2.seconds,
    private val attempts: Int = 3,
) : DeviceService {
    private class Lookup(val name: String, val queryId: Int, val callbacks: MutableList<(Resolution) -> Unit>) {
        var sends = 0
        var timer: Cancellable? = null
    }

    private val cache = linkedMapOf<String, Ipv4Address>()
    private val lookups = linkedMapOf<String, Lookup>()
    private var scope: WorkScope? = null
    private var nextQueryId = 1

    init {
        transport.bind(DnsProtocol.CLIENT_PORT) { onDatagram(it) }
    }

    override fun start(scope: WorkScope) { this.scope = scope }

    override fun stop() {
        scope = null
        cache.clear()
        lookups.clear()
    }

    fun resolve(name: String, callback: (Resolution) -> Unit) {
        val key = DnsProtocol.normalize(name)
        runCatching { Ipv4Address.parse(key) }.getOrNull()?.let { return callback(Resolution(key, it)) }
        cache[key]?.let { return callback(Resolution(key, it)) }
        val currentScope = scope ?: return callback(Resolution(key, null, "host is not running"))
        val server = transport.config?.dnsServer ?: return callback(Resolution(key, null, "no DNS server configured"))

        lookups[key]?.let { it.callbacks += callback; return }
        val lookup = Lookup(key, nextQueryId++, mutableListOf(callback))
        lookups[key] = lookup
        send(lookup, server, currentScope)
    }

    private fun send(lookup: Lookup, server: Ipv4Address, scope: WorkScope) {
        lookup.sends++
        if (!transport.sendUdp(DnsProtocol.CLIENT_PORT, server, DnsProtocol.SERVER_PORT, DnsMessage.Query(lookup.queryId, lookup.name))) {
            return finish(lookup, Resolution(lookup.name, null, transport.explainUnreachable(server) ?: "the network is unreachable"))
        }
        lookup.timer = scope.schedule(retryInterval) {
            if (lookups[lookup.name] !== lookup) return@schedule
            if (lookup.sends < attempts) {
                send(lookup, server, scope)
            } else {
                val cause = transport.explainUnreachable(server) ?: "DNS server $server did not answer"
                finish(lookup, Resolution(lookup.name, null, cause, timedOut = true))
            }
        }
    }

    private fun onDatagram(d: ReceivedDatagram) {
        val answer = d.datagram.payload as? DnsMessage.Answer ?: return
        val lookup = lookups[answer.name]?.takeIf { it.queryId == answer.queryId } ?: return
        if (answer.status == DnsStatus.NOERROR && answer.address != null) {
            cache[answer.name] = answer.address
            events.emit(id, EventPayload.ProtocolStateChanged(PROTOCOL, "QUERYING", "RESOLVED", "${answer.name} A ${answer.address}"))
            finish(lookup, Resolution(answer.name, answer.address))
        } else {
            events.emit(id, EventPayload.ProtocolStateChanged(PROTOCOL, "QUERYING", "NXDOMAIN", answer.name))
            finish(lookup, Resolution(answer.name, null, "no such host '${answer.name}'"))
        }
    }

    private fun finish(lookup: Lookup, resolution: Resolution) {
        lookup.timer?.cancel()
        lookups -= lookup.name
        lookup.callbacks.forEach { it(resolution) }
    }

    override fun snapshot() = ObjectSnapshot(
        id, PROTOCOL, ObjectKind.PROTOCOL,
        state = mapOf(
            "server" to transport.config?.dnsServer?.toString(),
            "cache" to cache.map { (name, address) -> mapOf("name" to name, "address" to address.toString()) },
            "pending" to lookups.keys.toList(),
        ),
        relations = mapOf("interface" to listOf(transport.interfaceId)),
    )

    companion object {
        const val PROTOCOL = "dns-resolver"
    }
}
