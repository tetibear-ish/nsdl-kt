package com.a2z.nsdl.print

import com.a2z.nsdl.net.UdpPayload

/**
 * A deliberately small teaching protocol carried over UDP. It models print-job boundaries and
 * chunking without claiming TCP reliability, ordering, retransmission, or a wire-compatible IPP.
 */
object PrintProtocol {
    const val SERVER_PORT = 9100
    const val CLIENT_PORT = 49152
}

enum class PrintReplyStatus { ACCEPTED, REJECTED }

sealed interface PrintMessage : UdpPayload {
    val jobId: String

    data class Start(
        override val jobId: String,
        val documentName: String,
        val totalBytes: Int,
        val chunkCount: Int,
    ) : PrintMessage {
        override fun describe() = "PRINT START job=$jobId document=$documentName bytes=$totalBytes chunks=$chunkCount"
    }

    data class Chunk(override val jobId: String, val index: Int, val bytes: Int) : PrintMessage {
        override fun describe() = "PRINT DATA job=$jobId chunk=$index bytes=$bytes"
    }

    data class Complete(override val jobId: String) : PrintMessage {
        override fun describe() = "PRINT COMPLETE job=$jobId"
    }

    data class Reply(override val jobId: String, val status: PrintReplyStatus, val detail: String) : PrintMessage {
        override fun describe() = "PRINT REPLY job=$jobId status=$status detail=$detail"
    }
}
