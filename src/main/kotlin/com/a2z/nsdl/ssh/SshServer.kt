package com.a2z.nsdl.ssh

import com.a2z.nsdl.device.DeviceService
import com.a2z.nsdl.ip.ReceivedDatagram
import com.a2z.nsdl.ip.UdpTransport
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.sim.WorkScope

data class CompletedFileTransfer(val name: String, val bytes: Int, val chunks: Int, val source: Ipv4Address)
data class ExecutedCommand(val command: String, val exitCode: Int, val source: Ipv4Address)

/**
 * The server half of the teaching SSH protocol: version echo, password authentication against one
 * configured account, a tiny table of canned command outputs, and chunked file receipt (validated the
 * same way [com.a2z.nsdl.print.PrintServer] validates a print job). One session per source address at
 * a time; a second Hello/AuthRequest from the same address simply replaces it.
 */
class SshServer(
    override val id: ObjectId,
    private val transport: UdpTransport,
    private val username: String,
    private val password: String,
    private val serverVersion: String = "SSH-nsdl-server-1.0",
) : DeviceService {
    private data class IncomingFile(
        val totalBytes: Int,
        val chunkCount: Int,
        val sourceMac: MacAddress,
        val chunks: MutableMap<Int, Int> = mutableMapOf(),
    )

    private class Session(var authenticated: Boolean) {
        var incoming: IncomingFile? = null
    }

    private var started = false
    private val sessions = mutableMapOf<Ipv4Address, Session>()
    private val mutableCompletedFiles = mutableListOf<CompletedFileTransfer>()
    private val mutableExecutedCommands = mutableListOf<ExecutedCommand>()
    val completedFiles: List<CompletedFileTransfer> get() = mutableCompletedFiles.toList()
    val executedCommands: List<ExecutedCommand> get() = mutableExecutedCommands.toList()

    init { transport.bind(SshProtocol.SERVER_PORT, ::receive) }

    override fun start(scope: WorkScope) { started = true }
    override fun stop() { started = false; sessions.clear() }

    private fun receive(received: ReceivedDatagram) {
        if (!started) return
        val source = received.packet.src
        val mac = received.srcMac
        when (val message = received.datagram.payload as? SshMessage ?: return) {
            is SshMessage.Hello -> reply(source, mac, SshMessage.Hello(serverVersion))
            is SshMessage.AuthRequest -> {
                val accepted = message.username == username && message.password == password
                sessions[source] = Session(authenticated = accepted)
                reply(
                    source, mac,
                    SshMessage.AuthReply(
                        if (accepted) SshAuthStatus.ACCEPTED else SshAuthStatus.REJECTED,
                        if (accepted) "welcome, ${message.username}" else "invalid credentials",
                    ),
                )
            }
            is SshMessage.Exec -> {
                if (sessions[source]?.authenticated != true) return
                val (text, exitCode) = execute(message.command)
                mutableExecutedCommands += ExecutedCommand(message.command, exitCode, source)
                reply(source, mac, SshMessage.Output(message.command, text, exitCode))
            }
            is SshMessage.FileStart -> {
                val session = sessions[source]?.takeIf { it.authenticated } ?: return
                if (message.totalBytes >= 0 && message.chunkCount >= 0) {
                    session.incoming = IncomingFile(message.totalBytes, message.chunkCount, mac)
                }
            }
            is SshMessage.FileChunk -> {
                val incoming = sessions[source]?.incoming ?: return
                if (message.index in 0 until incoming.chunkCount && message.bytes >= 0 && message.index !in incoming.chunks) {
                    incoming.chunks[message.index] = message.bytes
                }
            }
            is SshMessage.FileComplete -> completeTransfer(source, mac, message.name)
            is SshMessage.Disconnect -> sessions.remove(source)
            is SshMessage.AuthReply, is SshMessage.Output, is SshMessage.FileResult -> Unit
        }
    }

    private fun completeTransfer(source: Ipv4Address, mac: MacAddress, name: String) {
        val session = sessions[source] ?: return
        val incoming = session.incoming ?: return
        session.incoming = null
        val valid = incoming.chunks.size == incoming.chunkCount &&
            incoming.chunks.keys == (0 until incoming.chunkCount).toSet() &&
            incoming.chunks.values.sum() == incoming.totalBytes
        if (valid) mutableCompletedFiles += CompletedFileTransfer(name, incoming.totalBytes, incoming.chunkCount, source)
        reply(source, mac, SshMessage.FileResult(name, valid, if (valid) "stored" else "incomplete transfer"))
    }

    /** A tiny, fixed table: enough to demonstrate a real command/output round trip, not a real shell. */
    private fun execute(command: String): Pair<String, Int> = when (command) {
        "neofetch" -> "nsdl-linux ~ uptime 0:04 ~ shell nsdl-sh ~ packages 0 (simulated host)" to 0
        "whoami" -> username to 0
        "pwd" -> "/home/$username" to 0
        else -> "bash: $command: command not found" to 127
    }

    private fun reply(dst: Ipv4Address, dstMac: MacAddress, message: SshMessage) =
        transport.sendUdp(SshProtocol.SERVER_PORT, dst, SshProtocol.CLIENT_PORT, message, dstMac)

    override fun snapshot() = ObjectSnapshot(
        id, "ssh-server", ObjectKind.PROTOCOL,
        state = mapOf(
            "started" to started,
            "authenticatedSessions" to sessions.count { it.value.authenticated },
            "executedCommands" to executedCommands.map { mapOf("command" to it.command, "exitCode" to it.exitCode, "source" to it.source.toString()) },
            "completedFiles" to completedFiles.map { mapOf("name" to it.name, "bytes" to it.bytes, "chunks" to it.chunks, "source" to it.source.toString()) },
        ),
        relations = mapOf("interface" to listOf(transport.interfaceId)),
    )
}
