package com.a2z.nsdl.web

import com.a2z.nsdl.app.CommandError
import com.a2z.nsdl.app.CommandResult
import com.a2z.nsdl.app.ErrorCode
import com.a2z.nsdl.events.Delivery
import com.a2z.nsdl.events.EventFilter
import com.a2z.nsdl.events.SubscribeResult
import com.a2z.nsdl.ipc.DecodeResult
import com.a2z.nsdl.ipc.IpcOperation
import com.a2z.nsdl.ipc.ReplyCodec
import com.a2z.nsdl.ipc.RequestCodec
import com.a2z.nsdl.runtime.Request
import com.a2z.nsdl.runtime.SimulationRuntime
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Loopback HTTP adapter for browser clients: static UI, command requests, and SSE event delivery. */
class HttpSimulationServer(
    private val runtime: SimulationRuntime,
    port: Int = 0,
    private val maxRequestBytes: Int = 1_048_576,
) {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
    private val executor: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "nsdl-http").apply { isDaemon = true }
    }

    val port: Int get() = server.address.port

    init {
        server.executor = executor
        server.createContext("/api/command", ::handleCommand)
        server.createContext("/api/events", ::handleEvents)
        server.createContext("/", ::handleStatic)
    }

    fun start() = server.start()

    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handleCommand(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return exchange.respond(405, "text/plain", "method not allowed")
        val bytes = exchange.requestBody.readNBytes(maxRequestBytes + 1)
        if (bytes.size > maxRequestBytes) return exchange.respond(413, "text/plain", "request too large")
        val line = bytes.toString(StandardCharsets.UTF_8)
        val response = when (val decoded = RequestCodec.decode(line)) {
            is DecodeResult.Failed -> ReplyCodec.encodeError(decoded.id, decoded.error)
            is DecodeResult.Decoded -> when (val operation = decoded.operation) {
                is IpcOperation.Run -> {
                    val outcome = runtime.submit(Request(operation.command, decoded.id))
                    when (val result = outcome.result) {
                        is CommandResult.Ok -> ReplyCodec.encodeResult(decoded.id, outcome.revision, result.changed, result.data)
                        is CommandResult.Rejected -> ReplyCodec.encodeError(decoded.id, result.error)
                    }
                }
                else -> ReplyCodec.encodeError(
                    decoded.id,
                    CommandError(ErrorCode.INVALID_REQUEST, "subscriptions use GET /api/events"),
                )
            }
        }
        exchange.respond(200, "application/json", response)
    }

    private fun handleEvents(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return exchange.respond(405, "text/plain", "method not allowed")
        val from = query(exchange)["from"]?.toLongOrNull() ?: 0L
        when (val subscribed = runtime.subscribe(EventFilter(), from, capacity = 1_000)) {
            SubscribeResult.CursorExpired -> exchange.respond(
                409,
                "application/json",
                ReplyCodec.encodeError(null, CommandError(ErrorCode.CURSOR_EXPIRED, "cursor is older than retained history")),
            )
            is SubscribeResult.Subscribed -> {
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                exchange.responseHeaders.set("Cache-Control", "no-cache")
                exchange.responseHeaders.set("X-Accel-Buffering", "no")
                exchange.sendResponseHeaders(200, 0)
                val output = exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8)
                try {
                    output.write(": connected\n\n")
                    output.flush()
                    while (!Thread.currentThread().isInterrupted && !subscribed.subscription.isClosed) {
                        when (val delivery = subscribed.subscription.poll()) {
                            is Delivery.Event -> output.write("data: ${ReplyCodec.encodeEvent("web", delivery.record)}\n\n")
                            is Delivery.Gap -> {
                                output.write("data: ${ReplyCodec.encodeGap("web")}\n\n")
                                output.flush()
                                break
                            }
                            null -> {
                                Thread.sleep(10)
                                continue
                            }
                        }
                        output.flush()
                    }
                } catch (_: IOException) {
                    // Browser disconnected.
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    subscribed.subscription.close()
                    exchange.close()
                }
            }
        }
    }

    private fun handleStatic(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return exchange.respond(405, "text/plain", "method not allowed")
        val path = when (exchange.requestURI.path) {
            "/" -> "index.html"
            "/app.js" -> "app.js"
            "/remote.js" -> "remote.js"
            "/styles.css" -> "styles.css"
            else -> return exchange.respond(404, "text/plain", "not found")
        }
        val resource = when (path) {
            "index.html" -> "/web/server-index.html"
            "remote.js" -> "/web/remote.js"
            else -> "/$path"
        }
        val bytes = javaClass.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: return exchange.respond(404, "text/plain", "not found")
        val contentType = when {
            path.endsWith(".html") -> "text/html; charset=utf-8"
            path.endsWith(".js") -> "text/javascript; charset=utf-8"
            path.endsWith(".css") -> "text/css; charset=utf-8"
            else -> "application/octet-stream"
        }
        exchange.responseHeaders.set("Content-Security-Policy", "default-src 'self'; style-src 'self'; connect-src 'self'")
        exchange.respond(200, contentType, bytes)
    }

    private fun query(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery?.split('&')?.filter { it.isNotBlank() }?.associate { field ->
            val pieces = field.split('=', limit = 2)
            URLDecoder.decode(pieces[0], StandardCharsets.UTF_8) to
                URLDecoder.decode(pieces.getOrElse(1) { "" }, StandardCharsets.UTF_8)
        } ?: emptyMap()

    private fun HttpExchange.respond(status: Int, contentType: String, body: String) =
        respond(status, contentType, body.toByteArray(StandardCharsets.UTF_8))

    private fun HttpExchange.respond(status: Int, contentType: String, body: ByteArray) {
        responseHeaders.set("Content-Type", contentType)
        responseHeaders.set("X-Content-Type-Options", "nosniff")
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }
}
