package com.a2z.nsdl.print

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

enum class PrintJobStatus { SENDING, AWAITING_REPLY, ACKNOWLEDGED, REJECTED }

data class OutgoingPrintJob(
    val id: String,
    val documentName: String,
    val bytes: Int,
    val chunks: Int,
    val status: PrintJobStatus,
    val detail: String = "",
)

class PrintClient(override val id: ObjectId, private val transport: UdpTransport) : DeviceService, Actionable {
    private var started = false
    private val mutableJobs = linkedMapOf<String, OutgoingPrintJob>()
    private val completions = mutableMapOf<String, (OutgoingPrintJob) -> Unit>()
    val jobs: List<OutgoingPrintJob> get() = mutableJobs.values.toList()

    init {
        transport.bind(PrintProtocol.CLIENT_PORT) { received ->
            val reply = received.datagram.payload as? PrintMessage.Reply ?: return@bind
            val job = mutableJobs[reply.jobId] ?: return@bind
            val updated = job.copy(
                status = if (reply.status == PrintReplyStatus.ACCEPTED) PrintJobStatus.ACKNOWLEDGED else PrintJobStatus.REJECTED,
                detail = reply.detail,
            )
            mutableJobs[reply.jobId] = updated
            completions.remove(reply.jobId)?.invoke(updated)
        }
    }

    override fun start(scope: WorkScope) { started = true }
    override fun stop() { started = false; completions.clear() }

    /**
     * Sends a job. [printerMac] is normally omitted and resolved with ARP. [onReply] runs once when the
     * printer's reply arrives; it never runs if the job could not be sent (this returns false) or no
     * reply ever comes.
     */
    fun submit(
        jobId: String,
        documentName: String,
        bytes: Int,
        printerAddress: Ipv4Address,
        printerMac: MacAddress? = null,
        chunkSize: Int = 1_000,
        onReply: ((OutgoingPrintJob) -> Unit)? = null,
    ): Boolean {
        require(jobId.isNotBlank()) { "job id must not be blank" }
        require(documentName.isNotBlank()) { "document name must not be blank" }
        require(bytes >= 0) { "byte count must not be negative" }
        require(chunkSize > 0) { "chunk size must be positive" }
        if (!started || !transport.linkUp || transport.config == null || jobId in mutableJobs) return false

        val chunks = if (bytes == 0) 0 else (bytes + chunkSize - 1) / chunkSize
        mutableJobs[jobId] = OutgoingPrintJob(jobId, documentName, bytes, chunks, PrintJobStatus.SENDING)
        if (!send(printerAddress, printerMac, PrintMessage.Start(jobId, documentName, bytes, chunks))) return fail(jobId)
        repeat(chunks) { index ->
            val chunkBytes = minOf(chunkSize, bytes - index * chunkSize)
            if (!send(printerAddress, printerMac, PrintMessage.Chunk(jobId, index, chunkBytes))) return fail(jobId)
        }
        if (!send(printerAddress, printerMac, PrintMessage.Complete(jobId))) return fail(jobId)
        mutableJobs[jobId] = mutableJobs.getValue(jobId).copy(status = PrintJobStatus.AWAITING_REPLY)
        onReply?.let { completions[jobId] = it }
        return true
    }

    /** The only scripted action: "submit" forwards its params to [submit]. Invalid params reject, never throw. */
    override fun perform(action: String, params: Map<String, Any?>): ActionOutcome {
        if (action != "submit") return ActionOutcome(accepted = false, detail = "unknown action '$action'")
        val jobId = params["jobId"] as? String ?: return ActionOutcome(accepted = false, detail = "missing 'jobId'")
        val documentName = params["documentName"] as? String ?: return ActionOutcome(accepted = false, detail = "missing 'documentName'")
        val bytes = (params["bytes"] as? Number)?.toInt() ?: return ActionOutcome(accepted = false, detail = "missing 'bytes'")
        val printerAddress = when (val value = params["printerAddress"]) {
            is Ipv4Address -> value
            is String -> runCatching { Ipv4Address.parse(value) }.getOrNull()
            else -> null
        } ?: return ActionOutcome(accepted = false, detail = "missing or invalid 'printerAddress'")
        val printerMac = when (val value = params["printerMac"]) {
            null -> null
            is MacAddress -> value
            is String -> runCatching { MacAddress.parse(value) }.getOrNull() ?: return ActionOutcome(accepted = false, detail = "invalid 'printerMac'")
            else -> return ActionOutcome(accepted = false, detail = "invalid 'printerMac'")
        }
        val chunkSize = (params["chunkSize"] as? Number)?.toInt() ?: 1_000

        val accepted = try {
            submit(jobId, documentName, bytes, printerAddress, printerMac, chunkSize)
        } catch (e: IllegalArgumentException) {
            return ActionOutcome(accepted = false, detail = e.message ?: "invalid submit params")
        }
        val job = mutableJobs[jobId]
        return ActionOutcome(
            accepted = accepted,
            detail = if (accepted) "job '$jobId' submitted" else job?.detail ?: "job '$jobId' was not accepted",
            data = mapOf("jobId" to jobId, "status" to (job?.status?.name ?: PrintJobStatus.REJECTED.name)),
        )
    }

    private fun send(dst: Ipv4Address, mac: MacAddress?, message: PrintMessage) =
        transport.sendUdp(PrintProtocol.CLIENT_PORT, dst, PrintProtocol.SERVER_PORT, message, mac)

    private fun fail(jobId: String): Boolean {
        mutableJobs[jobId] = mutableJobs.getValue(jobId).copy(status = PrintJobStatus.REJECTED, detail = "link unavailable")
        return false
    }

    override fun snapshot() = ObjectSnapshot(
        id, "print-client", ObjectKind.PROTOCOL,
        state = mapOf(
            "started" to started,
            "jobs" to jobs.map { mapOf("id" to it.id, "document" to it.documentName, "bytes" to it.bytes, "chunks" to it.chunks, "status" to it.status.name, "detail" to it.detail) },
        ),
        relations = mapOf("interface" to listOf(transport.interfaceId)),
    )
}
