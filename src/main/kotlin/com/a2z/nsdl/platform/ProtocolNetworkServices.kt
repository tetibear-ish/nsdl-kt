package com.a2z.nsdl.platform

import com.a2z.nsdl.dns.DnsResolver
import com.a2z.nsdl.http.WebClient
import com.a2z.nsdl.ip.UdpTransport
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.print.PrintClient
import com.a2z.nsdl.print.PrintJobStatus
import com.a2z.nsdl.software.HostAddress
import com.a2z.nsdl.software.NetworkServices
import com.a2z.nsdl.software.Outcome
import com.a2z.nsdl.software.RemoteOutput
import com.a2z.nsdl.software.WebDocument
import com.a2z.nsdl.ssh.SshClient
import com.a2z.nsdl.ssh.SshSessionStatus

/**
 * Implements the software layer's [NetworkServices] with a host's protocol clients. This is the only
 * place that translates between the two levels: user-level requests become protocol operations,
 * a [HostAddress] becomes an [Ipv4Address], protocol results become [Outcome]s, and the interface's
 * own view of reachability ([UdpTransport.explainUnreachable]) becomes [diagnose]'s plain text.
 *
 * Print jobs are named "<hostId>/<jobName>" on the wire, so the printer's records and the packets
 * name the same job the spooler shows, and jobs from different hosts never collide at the printer.
 */
class ProtocolNetworkServices(
    private val hostId: ObjectId,
    private val transport: UdpTransport,
    private val resolver: DnsResolver,
    private val web: WebClient,
    private val print: PrintClient,
    private val ssh: SshClient,
) : NetworkServices {
    override fun resolve(name: String, done: (Outcome<HostAddress>) -> Unit) {
        resolver.resolve(name) { resolution ->
            done(
                resolution.address?.let { Outcome.Success(HostAddress(it.toString())) }
                    ?: Outcome.Failure(resolution.error ?: "unknown host", resolution.timedOut),
            )
        }
    }

    override fun diagnose(server: HostAddress): String? = server.toIpv4()?.let(transport::explainUnreachable)

    private fun unreachable(address: Ipv4Address) = Outcome.Failure(transport.explainUnreachable(address) ?: "the network is unreachable")

    override fun fetchPage(server: HostAddress, host: String, path: String, done: (Outcome<WebDocument>) -> Unit) {
        val address = server.toIpv4() ?: return done(Outcome.Failure("invalid address $server"))
        web.get(address, host, path) { outcome ->
            val response = outcome.response
            done(
                if (response == null) {
                    Outcome.Failure(outcome.error ?: "no response", outcome.timedOut)
                } else {
                    Outcome.Success(WebDocument(response.status, response.reason, response.page?.title ?: response.reason, response.page?.body ?: ""))
                },
            )
        }
    }

    override fun printDocument(printer: HostAddress, jobName: String, document: String, sizeBytes: Int, done: (Outcome<String>) -> Unit) {
        val address = printer.toIpv4() ?: return done(Outcome.Failure("invalid address $printer"))
        val sent = print.submit("${hostId.value}/$jobName", document, sizeBytes, address) { job ->
            done(
                if (job.status == PrintJobStatus.ACKNOWLEDGED) Outcome.Success(job.detail.ifEmpty { "printed" })
                else Outcome.Failure(job.detail.ifEmpty { "the printer rejected the job" }),
            )
        }
        if (!sent) done(unreachable(address))
    }

    override fun runRemoteCommand(server: HostAddress, user: String, password: String, command: String, done: (Outcome<RemoteOutput>) -> Unit) {
        val address = server.toIpv4() ?: return done(Outcome.Failure("invalid address $server"))
        if (ssh.busy) return done(Outcome.Failure("another SSH session is in progress"))
        val started = ssh.openSession(address, user, password, command) { session ->
            val output = session.lastOutput
            done(
                if (session.status == SshSessionStatus.CLOSED && output != null) Outcome.Success(RemoteOutput(output.text, output.exitCode))
                else Outcome.Failure("Permission denied (${session.detail.ifEmpty { "session failed" }})"),
            )
        }
        if (!started) done(unreachable(address))
    }

    private fun HostAddress.toIpv4(): Ipv4Address? = runCatching { Ipv4Address.parse(text) }.getOrNull()
}
