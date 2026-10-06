package com.a2z.nsdl.ssh

import com.a2z.nsdl.device.DeviceService
import com.a2z.nsdl.ip.UdpTransport
import com.a2z.nsdl.model.ActionOutcome
import com.a2z.nsdl.model.Actionable
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.sim.WorkScope

enum class SshSessionStatus { HELLO_SENT, AUTH_SENT, COMMAND_SENT, TRANSFERRING, FAILED, CLOSED }

data class SshCommandOutput(val command: String, val text: String, val exitCode: Int)

/** One scripted session's progress. Mutated reactively as the server's replies arrive; see [SshClient]. */
data class SshSession(
    val serverAddress: Ipv4Address,
    val serverMac: MacAddress?,
    val username: String,
    val password: String,
    val command: String,
    /** Null skips the file transfer: the session disconnects right after the command's output. */
    val fileName: String?,
    val fileBytes: Int,
    val chunkSize: Int,
    var status: SshSessionStatus = SshSessionStatus.HELLO_SENT,
    var detail: String = "",
    var lastOutput: SshCommandOutput? = null,
    var transferAccepted: Boolean? = null,
)

/**
 * The client half of the teaching SSH protocol. A single Actionable action, "openSession", scripts an
 * entire session: version hello, password auth, one exec'd command, then (optionally) one chunked file
 * transfer, ending in a disconnect -- each step triggered by the previous step's reply, the same reactive style
 * [com.a2z.nsdl.dhcp.DhcpClient] uses for its own state machine. Only one session may be open at a time;
 * there is no interactive shell, multiple commands, or concurrent sessions in this teaching model.
 */
class SshClient(override val id: ObjectId, private val transport: UdpTransport) : DeviceService, Actionable {
    private var started = false
    var session: SshSession? = null
        private set
    private var onFinished: ((SshSession) -> Unit)? = null

    /** Whether a session is in progress; only one may be open at a time. */
    val busy: Boolean
        get() = session?.let { it.status != SshSessionStatus.CLOSED && it.status != SshSessionStatus.FAILED } == true

    init {
        transport.bind(SshProtocol.CLIENT_PORT) { received ->
            val current = session ?: return@bind
            when (val message = received.datagram.payload as? SshMessage ?: return@bind) {
                is SshMessage.Hello -> if (current.status == SshSessionStatus.HELLO_SENT) {
                    current.status = SshSessionStatus.AUTH_SENT
                    send(current, SshMessage.AuthRequest(current.username, current.password))
                }
                is SshMessage.AuthReply -> if (current.status == SshSessionStatus.AUTH_SENT) {
                    if (message.status == SshAuthStatus.ACCEPTED) {
                        current.status = SshSessionStatus.COMMAND_SENT
                        send(current, SshMessage.Exec(current.command))
                    } else {
                        current.status = SshSessionStatus.FAILED
                        current.detail = message.detail
                        finished(current)
                    }
                }
                is SshMessage.Output -> if (current.status == SshSessionStatus.COMMAND_SENT) {
                    current.lastOutput = SshCommandOutput(message.command, message.text, message.exitCode)
                    if (current.fileName == null) {
                        send(current, SshMessage.Disconnect("session complete"))
                        current.status = SshSessionStatus.CLOSED
                        finished(current)
                    } else {
                        current.status = SshSessionStatus.TRANSFERRING
                        startTransfer(current, current.fileName)
                    }
                }
                is SshMessage.FileResult -> if (current.status == SshSessionStatus.TRANSFERRING) {
                    current.transferAccepted = message.accepted
                    current.detail = message.detail
                    send(current, SshMessage.Disconnect("session complete"))
                    current.status = SshSessionStatus.CLOSED
                    finished(current)
                }
                else -> Unit
            }
        }
    }

    override fun start(scope: WorkScope) { started = true }
    override fun stop() { started = false; session = null; onFinished = null }

    /**
     * Starts a session. [serverMac] is normally omitted and resolved with ARP. [onFinished] runs once
     * when the session closes or fails; it never runs if this returns false (busy or link unavailable)
     * or the server never answers. Returns whether the session was started.
     */
    fun openSession(
        serverAddress: Ipv4Address,
        username: String,
        password: String,
        command: String,
        serverMac: MacAddress? = null,
        fileName: String? = null,
        fileBytes: Int = 0,
        chunkSize: Int = 1_000,
        onFinished: ((SshSession) -> Unit)? = null,
    ): Boolean {
        if (busy || !started || !transport.linkUp || transport.config == null) return false
        val newSession = SshSession(serverAddress, serverMac, username, password, command, fileName, fileBytes, chunkSize)
        session = newSession
        this.onFinished = onFinished
        send(newSession, SshMessage.Hello(CLIENT_VERSION))
        return true
    }

    private fun finished(session: SshSession) {
        val callback = onFinished ?: return
        onFinished = null
        callback(session)
    }

    override fun perform(action: String, params: Map<String, Any?>): ActionOutcome {
        if (action != "openSession") return ActionOutcome(accepted = false, detail = "unknown action '$action'")
        val current = session
        if (busy && current != null) {
            return ActionOutcome(accepted = false, detail = "a session is already open (status=${current.status})")
        }
        val username = params["username"] as? String ?: return ActionOutcome(accepted = false, detail = "missing 'username'")
        val password = params["password"] as? String ?: return ActionOutcome(accepted = false, detail = "missing 'password'")
        val command = params["command"] as? String ?: return ActionOutcome(accepted = false, detail = "missing 'command'")
        val fileName = params["fileName"] as? String
        val fileBytes = (params["fileBytes"] as? Number)?.toInt()
            ?: if (fileName == null) 0 else return ActionOutcome(accepted = false, detail = "missing 'fileBytes'")
        val serverAddress = when (val value = params["serverAddress"]) {
            is Ipv4Address -> value
            is String -> runCatching { Ipv4Address.parse(value) }.getOrNull()
            else -> null
        } ?: return ActionOutcome(accepted = false, detail = "missing or invalid 'serverAddress'")
        val serverMac = when (val value = params["serverMac"]) {
            null -> null
            is MacAddress -> value
            is String -> runCatching { MacAddress.parse(value) }.getOrNull() ?: return ActionOutcome(accepted = false, detail = "invalid 'serverMac'")
            else -> return ActionOutcome(accepted = false, detail = "invalid 'serverMac'")
        }
        val chunkSize = (params["chunkSize"] as? Number)?.toInt() ?: 1_000

        if (!openSession(serverAddress, username, password, command, serverMac, fileName, fileBytes, chunkSize)) {
            return ActionOutcome(accepted = false, detail = "link unavailable")
        }
        return ActionOutcome(accepted = true, detail = "session opened", data = mapOf("status" to session!!.status.name))
    }

    private fun startTransfer(session: SshSession, fileName: String) {
        val chunkCount = if (session.fileBytes == 0) 0 else (session.fileBytes + session.chunkSize - 1) / session.chunkSize
        send(session, SshMessage.FileStart(fileName, session.fileBytes, chunkCount))
        repeat(chunkCount) { index ->
            val bytes = minOf(session.chunkSize, session.fileBytes - index * session.chunkSize)
            send(session, SshMessage.FileChunk(fileName, index, bytes))
        }
        send(session, SshMessage.FileComplete(fileName))
    }

    private fun send(session: SshSession, message: SshMessage) =
        transport.sendUdp(SshProtocol.CLIENT_PORT, session.serverAddress, SshProtocol.SERVER_PORT, message, session.serverMac)

    override fun snapshot() = ObjectSnapshot(
        id, "ssh-client", ObjectKind.PROTOCOL,
        state = mapOf(
            "started" to started,
            "session" to session?.let {
                mapOf(
                    "status" to it.status.name,
                    "detail" to it.detail,
                    "command" to it.command,
                    "output" to it.lastOutput?.let { output -> mapOf("text" to output.text, "exitCode" to output.exitCode) },
                    "transferAccepted" to it.transferAccepted,
                )
            },
        ),
        relations = mapOf("interface" to listOf(transport.interfaceId)),
    )

    companion object {
        private const val CLIENT_VERSION = "SSH-nsdl-client-1.0"
    }
}
