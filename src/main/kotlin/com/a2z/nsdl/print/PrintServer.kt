package com.a2z.nsdl.print

import com.a2z.nsdl.device.DeviceService
import com.a2z.nsdl.ip.ReceivedDatagram
import com.a2z.nsdl.ip.UdpTransport
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.sim.WorkScope

data class CompletedPrintJob(
    val id: String,
    val documentName: String,
    val bytes: Int,
    val chunks: Int,
    val source: Ipv4Address,
)

class PrintServer(override val id: ObjectId, private val transport: UdpTransport) : DeviceService {
    private data class Incoming(
        val documentName: String,
        val totalBytes: Int,
        val chunkCount: Int,
        val sourceIp: Ipv4Address,
        val sourceMac: MacAddress,
        val chunks: MutableMap<Int, Int> = mutableMapOf(),
    )

    private var started = false
    private val incoming = mutableMapOf<String, Incoming>()
    private val mutableCompletedJobs = mutableListOf<CompletedPrintJob>()
    val completedJobs: List<CompletedPrintJob> get() = mutableCompletedJobs.toList()

    init { transport.bind(PrintProtocol.SERVER_PORT, ::receive) }

    override fun start(scope: WorkScope) { started = true }
    override fun stop() { started = false; incoming.clear() }

    private fun receive(received: ReceivedDatagram) {
        if (!started) return
        when (val message = received.datagram.payload as? PrintMessage ?: return) {
            is PrintMessage.Start -> if (
                message.jobId.isNotBlank() && message.documentName.isNotBlank() &&
                message.totalBytes >= 0 && message.chunkCount >= 0 && message.jobId !in incoming
            ) {
                incoming[message.jobId] = Incoming(
                    message.documentName, message.totalBytes, message.chunkCount,
                    received.packet.src, received.srcMac,
                )
            }
            is PrintMessage.Chunk -> incoming[message.jobId]?.let { job ->
                if (message.index in 0 until job.chunkCount && message.bytes >= 0 && message.index !in job.chunks) {
                    job.chunks[message.index] = message.bytes
                }
            }
            is PrintMessage.Complete -> complete(message.jobId)
            is PrintMessage.Reply -> Unit
        }
    }

    private fun complete(jobId: String) {
        val job = incoming.remove(jobId) ?: return
        val valid = job.chunks.size == job.chunkCount && job.chunks.keys == (0 until job.chunkCount).toSet() &&
            job.chunks.values.sum() == job.totalBytes
        if (valid) {
            mutableCompletedJobs += CompletedPrintJob(jobId, job.documentName, job.totalBytes, job.chunkCount, job.sourceIp)
        }
        transport.sendUdp(
            PrintProtocol.SERVER_PORT, job.sourceIp, PrintProtocol.CLIENT_PORT,
            PrintMessage.Reply(jobId, if (valid) PrintReplyStatus.ACCEPTED else PrintReplyStatus.REJECTED, if (valid) "queued" else "incomplete job"),
            job.sourceMac,
        )
    }

    override fun snapshot() = ObjectSnapshot(
        id, "print-server", ObjectKind.PROTOCOL,
        state = mapOf(
            "started" to started,
            "receiving" to incoming.keys.toList(),
            "completedJobs" to completedJobs.map { mapOf("id" to it.id, "document" to it.documentName, "bytes" to it.bytes, "chunks" to it.chunks, "source" to it.source.toString()) },
        ),
        relations = mapOf("interface" to listOf(transport.interfaceId)),
    )
}
