package com.a2z.nsdl

import com.a2z.nsdl.ipc.IpcClient
import com.a2z.nsdl.ipc.json.Json
import java.io.InputStream
import java.io.PrintStream

/** Interactive command loop backed exclusively by the public IPC protocol. */
class InteractiveShell(
    private val client: IpcClient,
    input: InputStream,
    private val out: PrintStream,
) {
    private val reader = input.bufferedReader()

    fun run() {
        out.println("NSDL interactive shell. Type 'help' for commands.")
        while (true) {
            out.print("nsdl> ")
            out.flush()
            val line = reader.readLine() ?: break
            val words = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            if (words[0] in setOf("quit", "exit")) break
            runCatching { execute(words) }
                .onFailure { out.println("error: ${it.message}") }
        }
    }

    private fun execute(words: List<String>) {
        when (words[0]) {
            "help" -> help()
            "types" -> request("listTypes")
            "list" -> request("listObjects")
            "create" -> {
                require(words.size >= 3) { "usage: create TYPE ID [PROPERTY=VALUE ...]" }
                request("create", mapOf("type" to words[1], "id" to words[2], "props" to properties(words.drop(3))))
            }
            "connect" -> {
                require(words.size == 4) { "usage: connect CABLE ENDPOINT_A ENDPOINT_B" }
                request("connect", mapOf("cableId" to words[1], "a" to words[2], "b" to words[3]))
            }
            "disconnect" -> {
                require(words.size == 2) { "usage: disconnect CABLE" }
                request("disconnect", mapOf("cableId" to words[1]))
            }
            "inspect" -> {
                require(words.size == 2) { "usage: inspect ID" }
                request("inspect", mapOf("id" to words[1]))
            }
            "power-on", "power-off" -> {
                require(words.size == 2) { "usage: ${words[0]} ID" }
                request(if (words[0] == "power-on") "powerOn" else "powerOff", mapOf("id" to words[1]))
            }
            "advance" -> {
                require(words.size == 2) { "usage: advance MILLISECONDS" }
                val millis = words[1].toLongOrNull() ?: throw IllegalArgumentException("milliseconds must be an integer")
                request("advance", mapOf("durationMs" to millis))
            }
            else -> out.println("error: unknown command '${words[0]}'; type 'help'")
        }
    }

    private fun request(op: String, params: Map<String, Any?> = emptyMap()) {
        val reply = client.request(op, params)
        if (reply["type"] == "error") {
            val error = reply["error"] as Map<*, *>
            out.println("error ${error["code"]}: ${error["message"]}")
            return
        }
        out.println("ok changed=${reply["changed"]} revision=${reply["revision"]}")
        reply["data"]?.let { out.println(Json.write(it)) }
    }

    private fun properties(words: List<String>): Map<String, String> = words.associate { word ->
        val separator = word.indexOf('=')
        require(separator > 0) { "property '$word' must be NAME=VALUE" }
        word.substring(0, separator) to word.substring(separator + 1)
    }

    private fun help() {
        out.println(
            """
            Commands:
              types
              create TYPE ID [PROPERTY=VALUE ...]
              list
              inspect ID
              connect CABLE ENDPOINT_A ENDPOINT_B
              disconnect CABLE
              power-on ID | power-off ID
              advance MILLISECONDS
              quit
            Example switch ports are addressed as switch1.port1, switch1.port2, ...
            """.trimIndent(),
        )
    }
}
