package com.a2z.nsdl.ipc

import com.a2z.nsdl.ipc.json.Json
import java.io.Closeable
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A minimal client for [IpcServer]: correlates requests with replies by id, and queues pushed messages. */
class IpcClient(host: String, port: Int) : Closeable {
    private val socket = Socket(host, port)
    private val out = socket.getOutputStream()
    private val writeLock = Any()
    private val pending = ConcurrentHashMap<String, CompletableFuture<Map<String, Any?>>>()
    private val pushed = LinkedBlockingQueue<Map<String, Any?>>()
    private val nextId = AtomicInteger(1)
    private val readerThread = Thread { readLoop() }.apply { isDaemon = true; name = "nsdl-ipc-client" }

    init {
        readerThread.start()
    }

    fun request(op: String, params: Map<String, Any?> = emptyMap(), id: String = "c${nextId.getAndIncrement()}"): Map<String, Any?> {
        val future = CompletableFuture<Map<String, Any?>>()
        pending[id] = future
        val line = Json.write(mapOf("v" to 1L, "id" to id, "op" to op, "params" to params))
        sendRawLine(line)
        return future.get(5, TimeUnit.SECONDS)
    }

    /** For tests that need to send something the normal request() can't build, e.g. malformed JSON. */
    fun sendRawLine(line: String) {
        synchronized(writeLock) {
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    /** Next pushed event or gap message, or null if none arrives within [timeoutMs]. */
    fun nextPushed(timeoutMs: Long = 2000): Map<String, Any?>? = pushed.poll(timeoutMs, TimeUnit.MILLISECONDS)

    private fun readLoop() {
        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        while (true) {
            val line = try {
                reader.readLine()
            } catch (e: Exception) {
                null
            } ?: break
            @Suppress("UNCHECKED_CAST")
            val message = Json.parse(line) as Map<String, Any?>
            val correlated = (message["id"] as? String)?.let { pending.remove(it) }
            if (correlated != null) correlated.complete(message) else pushed.add(message)
        }
    }

    override fun close() {
        socket.close()
    }
}
