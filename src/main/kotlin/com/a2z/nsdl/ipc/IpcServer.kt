package com.a2z.nsdl.ipc

import com.a2z.nsdl.app.CommandError
import com.a2z.nsdl.app.CommandResult
import com.a2z.nsdl.app.ErrorCode
import com.a2z.nsdl.events.Delivery
import com.a2z.nsdl.events.SubscribeResult
import com.a2z.nsdl.events.Subscription
import com.a2z.nsdl.runtime.Request
import com.a2z.nsdl.runtime.SimulationRuntime
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * NDJSON-over-TCP loopback server. Transport threads (one accept loop, one reader per connection)
 * only parse requests and submit them to [runtime]; nothing here touches simulation state directly.
 * Each connection has one writer lock, since both its reader thread (replies) and its subscriptions'
 * pump threads (pushed events) write to the same socket.
 */
class IpcServer(
    private val runtime: SimulationRuntime,
    port: Int = 0,
    private val maxLineLength: Int = 1_048_576,
) {
    private val serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = serverSocket.localPort

    @Volatile private var running = true
    private val connections = mutableListOf<Connection>()
    private val acceptThread = Thread { acceptLoop() }.apply { isDaemon = true; name = "nsdl-ipc-accept" }

    fun start() {
        acceptThread.start()
    }

    fun stop() {
        running = false
        runCatching { serverSocket.close() }
        synchronized(connections) { connections.toList() }.forEach { it.close() }
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                if (!running) return else continue
            }
            val connection = Connection(socket)
            synchronized(connections) { connections += connection }
            connection.start()
        }
    }

    private inner class Connection(private val socket: Socket) {
        private val writeLock = Any()
        private val out = socket.getOutputStream()
        private val subscriptions = mutableMapOf<String, Subscription>()
        private val nextSubId = AtomicInteger(1)

        @Volatile private var closed = false

        fun start() {
            Thread { readLoop() }.apply { isDaemon = true; name = "nsdl-ipc-conn" }.start()
        }

        private fun readLoop() {
            try {
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                while (!closed) {
                    val line = readLineBounded(reader) ?: break
                    if (line.isNotBlank()) handleLine(line)
                }
            } catch (e: IOException) {
                // connection dropped; fall through to cleanup
            } finally {
                close()
            }
        }

        private fun readLineBounded(reader: java.io.BufferedReader): String? {
            val sb = StringBuilder()
            while (true) {
                val c = reader.read()
                if (c == -1) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString()
                if (c != '\r'.code) sb.append(c.toChar())
                if (sb.length > maxLineLength) throw IOException("line exceeds maximum length of $maxLineLength")
            }
        }

        private fun handleLine(line: String) {
            when (val decoded = RequestCodec.decode(line)) {
                is DecodeResult.Failed -> writeLine(ReplyCodec.encodeError(decoded.id, decoded.error))
                is DecodeResult.Decoded -> handleOperation(decoded.id, decoded.operation)
            }
        }

        private fun handleOperation(id: String?, operation: IpcOperation) {
            when (operation) {
                is IpcOperation.Run -> {
                    val outcome = runtime.submit(Request(operation.command, id))
                    when (val result = outcome.result) {
                        is CommandResult.Ok -> writeLine(ReplyCodec.encodeResult(id, outcome.revision, result.changed, result.data))
                        is CommandResult.Rejected -> writeLine(ReplyCodec.encodeError(id, result.error))
                    }
                }
                is IpcOperation.Subscribe -> when (val sub = runtime.subscribe(operation.filter, operation.from, operation.capacity)) {
                    is SubscribeResult.Subscribed -> {
                        val subId = "sub-${nextSubId.getAndIncrement()}"
                        synchronized(subscriptions) { subscriptions[subId] = sub.subscription }
                        startPump(subId, sub.subscription)
                        writeLine(ReplyCodec.encodeResult(id, runtime.currentRevision(), true, mapOf("subscriptionId" to subId)))
                    }
                    SubscribeResult.CursorExpired ->
                        writeLine(ReplyCodec.encodeError(id, CommandError(ErrorCode.CURSOR_EXPIRED, "cursor is older than the retained history")))
                }
                is IpcOperation.Unsubscribe -> {
                    val sub = synchronized(subscriptions) { subscriptions.remove(operation.subscriptionId) }
                    sub?.close()
                    writeLine(ReplyCodec.encodeResult(id, runtime.currentRevision(), sub != null, null))
                }
            }
        }

        /** Polls one subscription's bounded queue on its own thread and pushes what it finds. */
        private fun startPump(subId: String, subscription: Subscription) {
            Thread {
                while (!closed) {
                    when (val delivery = subscription.poll()) {
                        is Delivery.Event -> writeLine(ReplyCodec.encodeEvent(subId, delivery.record))
                        is Delivery.Gap -> {
                            writeLine(ReplyCodec.encodeGap(subId))
                            synchronized(subscriptions) { subscriptions.remove(subId) }
                            return@Thread
                        }
                        null -> Thread.sleep(10)
                    }
                    if (subscription.isClosed) return@Thread
                }
            }.apply { isDaemon = true; name = "nsdl-ipc-pump-$subId" }.start()
        }

        private fun writeLine(line: String) {
            synchronized(writeLock) {
                if (closed) return
                try {
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                } catch (e: IOException) {
                    close()
                }
            }
        }

        fun close() {
            if (closed) return
            closed = true
            synchronized(subscriptions) { subscriptions.values.forEach { it.close() } }
            runCatching { socket.close() }
        }
    }
}
