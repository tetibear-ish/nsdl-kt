package com.a2z.nsdl.http

import com.a2z.nsdl.device.DeviceService
import com.a2z.nsdl.ip.ReceivedDatagram
import com.a2z.nsdl.ip.UdpTransport
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.sim.Cancellable
import com.a2z.nsdl.sim.WorkScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The outcome of one [WebClient.get]: a [response], or a human-readable [error] when none arrived.
 * [timedOut] marks an error where the request went out but nothing came back; [error] then says why,
 * as far as this host can tell.
 */
data class FetchOutcome(val response: HttpMessage.Response?, val error: String? = null, val timedOut: Boolean = false)

/**
 * Client half of the teaching web protocol. Each [get] sends one request and waits up to [timeout]
 * for the matching response; there is no retransmission. Outstanding requests are abandoned (their
 * callbacks never run) when the host stops.
 */
class WebClient(
    override val id: ObjectId,
    private val transport: UdpTransport,
    private val timeout: Duration = 5.seconds,
) : DeviceService {
    private class Request(val host: String, val path: String, val callback: (FetchOutcome) -> Unit) {
        var timer: Cancellable? = null
    }

    private val outstanding = linkedMapOf<Int, Request>()
    private var scope: WorkScope? = null
    private var nextRequestId = 1

    init {
        transport.bind(HttpProtocol.CLIENT_PORT) { onDatagram(it) }
    }

    override fun start(scope: WorkScope) { this.scope = scope }

    override fun stop() {
        scope = null
        outstanding.clear()
    }

    fun get(server: Ipv4Address, host: String, path: String, callback: (FetchOutcome) -> Unit) {
        val currentScope = scope ?: return callback(FetchOutcome(null, "host is not running"))
        val requestId = nextRequestId++
        val request = Request(host, path, callback)
        if (!transport.sendUdp(HttpProtocol.CLIENT_PORT, server, HttpProtocol.SERVER_PORT, HttpMessage.Get(requestId, host, path))) {
            return callback(FetchOutcome(null, transport.explainUnreachable(server) ?: "the network is unreachable"))
        }
        outstanding[requestId] = request
        request.timer = currentScope.schedule(timeout) {
            if (outstanding.remove(requestId) != null) {
                callback(FetchOutcome(null, transport.explainUnreachable(server) ?: "$host did not respond", timedOut = true))
            }
        }
    }

    private fun onDatagram(d: ReceivedDatagram) {
        val response = d.datagram.payload as? HttpMessage.Response ?: return
        val request = outstanding.remove(response.requestId) ?: return
        request.timer?.cancel()
        request.callback(FetchOutcome(response))
    }

    override fun snapshot() = ObjectSnapshot(
        id, "web-client", ObjectKind.PROTOCOL,
        state = mapOf("outstanding" to outstanding.values.map { "http://${it.host}${it.path}" }),
        relations = mapOf("interface" to listOf(transport.interfaceId)),
    )
}
