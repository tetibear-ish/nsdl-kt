package com.a2z.nsdl.icmp

import com.a2z.nsdl.device.DeviceService
import com.a2z.nsdl.ip.EchoTransport
import com.a2z.nsdl.ip.UdpTransport
import com.a2z.nsdl.model.ActionOutcome
import com.a2z.nsdl.model.Actionable
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.sim.WorkScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Sends one ICMP echo request per "ping" action ({"address": "192.168.1.101"}) and records what came
 * of it: a reply and its round-trip time in virtual milliseconds, or a timeout whose detail names the
 * likely cause from the interface's point of view (e.g. "no ARP reply from 192.168.1.101").
 * Results (most recent [RESULT_LIMIT]) are volatile, cleared when the host stops.
 */
class PingClient(
    override val id: ObjectId,
    private val transport: UdpTransport,
    private val echo: EchoTransport,
    private val events: EventSink,
    private val timeout: Duration = 2.seconds,
) : DeviceService, Actionable {
    enum class Status { WAITING, REPLIED, TIMED_OUT, UNREACHABLE }

    data class Result(val sequence: Int, val target: Ipv4Address, val sentAtMs: Long, val status: Status, val rttMs: Long? = null, val detail: String = "")

    private val results = linkedMapOf<Int, Result>()
    private var scope: WorkScope? = null
    private var nextSequence = 1

    init {
        echo.onEchoReply { from, reply ->
            val result = results[reply.sequence]?.takeIf { reply.identifier == IDENTIFIER && it.target == from && it.status == Status.WAITING }
            val now = scope?.now?.millis
            if (result != null && now != null) {
                val rtt = now - result.sentAtMs
                finish(result.copy(status = Status.REPLIED, rttMs = rtt, detail = "reply from $from in ${rtt}ms"))
            }
        }
    }

    override fun start(scope: WorkScope) { this.scope = scope }

    override fun stop() {
        scope = null
        results.clear()
    }

    override fun perform(action: String, params: Map<String, Any?>): ActionOutcome {
        if (action != "ping") return ActionOutcome(false, "unknown action '$action'; ping supports ping")
        val target = when (val value = params["address"]) {
            is Ipv4Address -> value
            is String -> runCatching { Ipv4Address.parse(value.trim()) }.getOrNull()
            else -> null
        } ?: return ActionOutcome(false, "missing or invalid 'address'")
        val currentScope = scope ?: return ActionOutcome(false, "the host is off")

        val sequence = nextSequence++
        val sent = Result(sequence, target, currentScope.now.millis, Status.WAITING)
        record(sent)
        if (!echo.sendEcho(target, IDENTIFIER, sequence)) {
            finish(sent.copy(status = Status.UNREACHABLE, detail = transport.explainUnreachable(target) ?: "the network is unreachable"))
            return ActionOutcome(true, "ping $sequence to $target could not be sent", mapOf("sequence" to sequence))
        }
        currentScope.schedule(timeout) {
            val waiting = results[sequence]?.takeIf { it.status == Status.WAITING } ?: return@schedule
            finish(waiting.copy(status = Status.TIMED_OUT, detail = "timed out: " + (transport.explainUnreachable(target) ?: "$target did not respond")))
        }
        return ActionOutcome(true, "ping $sequence sent to $target", mapOf("sequence" to sequence))
    }

    private fun record(result: Result) {
        results[result.sequence] = result
        while (results.size > RESULT_LIMIT) results.remove(results.keys.first())
    }

    private fun finish(result: Result) {
        record(result)
        events.emit(id, EventPayload.ProtocolStateChanged(PROTOCOL, Status.WAITING.name, result.status.name, "seq=${result.sequence} ${result.detail}"))
    }

    override fun snapshot() = ObjectSnapshot(
        id, PROTOCOL, ObjectKind.PROTOCOL,
        state = mapOf(
            "results" to results.values.map {
                mapOf(
                    "sequence" to it.sequence, "target" to it.target.toString(), "status" to it.status.name,
                    "rttMs" to it.rttMs, "detail" to it.detail,
                )
            },
        ),
        relations = mapOf("interface" to listOf(transport.interfaceId)),
    )

    companion object {
        const val PROTOCOL = "ping"
        private const val IDENTIFIER = 1
        private const val RESULT_LIMIT = 20
    }
}
