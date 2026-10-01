package com.a2z.nsdl.ssh

import com.a2z.nsdl.net.UdpPayload

/**
 * A deliberately small SSH-*shaped* teaching protocol carried over UDP: a version "handshake",
 * password authentication, one exec'd command, and one chunked file transfer. There is no real
 * cryptography (no key exchange, no encryption, no host-key verification), no interactive shell,
 * and no multiple channels or concurrent commands -- see docs/SCENARIOS.md for what this models
 * versus a real SSH session.
 */
object SshProtocol {
    const val SERVER_PORT = 22
    const val CLIENT_PORT = 49222
}

enum class SshAuthStatus { ACCEPTED, REJECTED }

sealed interface SshMessage : UdpPayload {
    data class Hello(val version: String) : SshMessage {
        override fun describe() = "SSH HELLO version=$version"
    }

    data class AuthRequest(val username: String, val password: String) : SshMessage {
        override fun describe() = "SSH AUTH user=$username"
    }

    data class AuthReply(val status: SshAuthStatus, val detail: String) : SshMessage {
        override fun describe() = "SSH AUTH-REPLY status=$status detail=$detail"
    }

    data class Exec(val command: String) : SshMessage {
        override fun describe() = "SSH EXEC command=$command"
    }

    data class Output(val command: String, val text: String, val exitCode: Int) : SshMessage {
        override fun describe() = "SSH OUTPUT command=$command exit=$exitCode"
    }

    data class FileStart(val name: String, val totalBytes: Int, val chunkCount: Int) : SshMessage {
        override fun describe() = "SSH FILE-START name=$name bytes=$totalBytes chunks=$chunkCount"
    }

    data class FileChunk(val name: String, val index: Int, val bytes: Int) : SshMessage {
        override fun describe() = "SSH FILE-CHUNK name=$name chunk=$index bytes=$bytes"
    }

    data class FileComplete(val name: String) : SshMessage {
        override fun describe() = "SSH FILE-COMPLETE name=$name"
    }

    data class FileResult(val name: String, val accepted: Boolean, val detail: String) : SshMessage {
        override fun describe() = "SSH FILE-RESULT name=$name accepted=$accepted detail=$detail"
    }

    data class Disconnect(val reason: String) : SshMessage {
        override fun describe() = "SSH DISCONNECT reason=$reason"
    }
}
