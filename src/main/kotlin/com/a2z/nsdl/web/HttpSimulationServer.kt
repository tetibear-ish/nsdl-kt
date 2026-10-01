package com.a2z.nsdl.web

import com.a2z.nsdl.Composition
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
import com.a2z.nsdl.ipc.json.Json
import com.a2z.nsdl.ipc.json.JsonParseException
import com.a2z.nsdl.runtime.MoveOutcome
import com.a2z.nsdl.runtime.MoveRequest
import com.a2z.nsdl.runtime.Position
import com.a2z.nsdl.runtime.Request
import com.a2z.nsdl.runtime.SessionDelivery
import com.a2z.nsdl.runtime.SessionSubscribeResult
import com.a2z.nsdl.runtime.SimulationRuntime
import com.a2z.nsdl.scenario.ScenarioRunner
import com.a2z.nsdl.scenario.teaching.ScenarioCatalog
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
    private val presence = PresenceRegistry()

    val port: Int get() = server.address.port

    init {
        server.executor = executor
        server.createContext("/api/command", ::handleCommand)
        server.createContext("/api/events", ::handleEvents)
        server.createContext("/api/scenarios", ::handleScenarios)
        server.createContext("/api/move", ::handleMove)
        server.createContext("/api/session/snapshot", ::handleSessionSnapshot)
        server.createContext("/api/session/stream", ::handleSessionStream)
        server.createContext("/api/presence", ::handlePresence)
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

    /**
     * Runs a named scenario end to end against its own fresh [Composition] -- separate from the live
     * lab this server otherwise exposes -- and returns its pass/fail result. This is how the browser
     * runs the same scenario the CLI's `scenario run` and a JUnit test do.
     */
    private fun handleScenarios(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        when {
            path == "/api/scenarios" && exchange.requestMethod == "GET" ->
                exchange.respond(200, "application/json", Json.write(mapOf("scenarios" to ScenarioCatalog.all.keys.sorted())))
            path == "/api/scenarios/run" && exchange.requestMethod == "POST" -> handleScenarioRun(exchange)
            path == "/api/scenarios" || path == "/api/scenarios/run" -> exchange.respond(405, "text/plain", "method not allowed")
            else -> exchange.respond(404, "text/plain", "not found")
        }
    }

    private fun handleScenarioRun(exchange: HttpExchange) {
        val bytes = exchange.requestBody.readNBytes(maxRequestBytes + 1)
        if (bytes.size > maxRequestBytes) return exchange.respond(413, "text/plain", "request too large")
        val body = try {
            Json.parse(bytes.toString(StandardCharsets.UTF_8)) as? Map<*, *> ?: emptyMap<String, Any?>()
        } catch (e: JsonParseException) {
            return exchange.respond(400, "text/plain", "malformed JSON: ${e.message}")
        }
        val name = body["name"] as? String ?: return exchange.respond(400, "text/plain", "missing 'name'")
        val factory = ScenarioCatalog.all[name] ?: return exchange.respond(404, "text/plain", "unknown scenario '$name'")
        val seed = (body["seed"] as? Long) ?: 0L

        val composition = Composition(seed)
        try {
            val result = ScenarioRunner(composition.runtime).run(factory())
            exchange.respond(200, "application/json", Json.write(result.toWireData()))
        } finally {
            composition.close()
        }
    }

    /**
     * `POST /api/move`: moves a node's shared canvas position. Body: `{id, x, y, clientId, requestId?,
     * baseRevision?}`. Positions are UI-only session state -- see [com.a2z.nsdl.runtime.CollabSession] --
     * so this never touches `/api/command`'s Command/CommandResult vocabulary except by reusing
     * [ErrorCode.IDEMPOTENCY_CONFLICT] and [ErrorCode.INVALID_REQUEST] for a consistent wire vocabulary.
     * Response: `{"type":"applied","revision":N}` or `{"type":"conflict","revision":N,"current":{x,y}|null}`.
     */
    private fun handleMove(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return exchange.respond(405, "text/plain", "method not allowed")
        val bytes = exchange.requestBody.readNBytes(maxRequestBytes + 1)
        if (bytes.size > maxRequestBytes) return exchange.respond(413, "text/plain", "request too large")
        val body = try {
            @Suppress("UNCHECKED_CAST")
            Json.parse(bytes.toString(StandardCharsets.UTF_8)) as? Map<String, Any?> ?: emptyMap()
        } catch (e: JsonParseException) {
            return exchange.respondError(ErrorCode.INVALID_REQUEST, "malformed JSON: ${e.message}")
        }
        val id = body["id"] as? String
        val x = (body["x"] as? Number)?.toDouble()
        val y = (body["y"] as? Number)?.toDouble()
        val clientId = body["clientId"] as? String
        if (id == null || x == null || y == null || clientId == null) {
            return exchange.respondError(ErrorCode.INVALID_REQUEST, "id, x, y and clientId are required")
        }
        val requestId = body["requestId"] as? String
        val baseRevision = (body["baseRevision"] as? Number)?.toLong()

        val response = when (val outcome = runtime.moveNode(MoveRequest(clientId, id, Position(x, y), requestId, baseRevision))) {
            is MoveOutcome.Applied -> mapOf("type" to "applied", "revision" to outcome.patch.revision)
            is MoveOutcome.Conflict -> mapOf(
                "type" to "conflict", "revision" to outcome.currentRevision,
                "current" to outcome.current?.let { mapOf("x" to it.x, "y" to it.y) },
            )
            MoveOutcome.IdempotencyConflict -> return exchange.respondError(
                ErrorCode.IDEMPOTENCY_CONFLICT, "request id '$requestId' was already used with a different move",
            )
        }
        exchange.respond(200, "application/json", Json.write(response))
    }

    /** `GET /api/session/snapshot`: the current full set of node positions, for a client's initial load
     * or after its stream cursor expired. Response: `{"revision":N,"positions":{id:{x,y}}}`. */
    private fun handleSessionSnapshot(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return exchange.respond(405, "text/plain", "method not allowed")
        val snapshot = runtime.sessionSnapshot()
        exchange.respond(
            200, "application/json",
            Json.write(mapOf("revision" to snapshot.revision, "positions" to snapshot.changed.mapValues { (_, p) -> mapOf("x" to p.x, "y" to p.y) })),
        )
    }

    /** `GET /api/session/stream?from=N` (SSE): streams position patches after revision [N], the
     * reconnect-from-revision path for the collaborative session (mirrors `/api/events`). */
    private fun handleSessionStream(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return exchange.respond(405, "text/plain", "method not allowed")
        val from = query(exchange)["from"]?.toLongOrNull() ?: 0L
        when (val subscribed = runtime.subscribeSession(from, capacity = 1_000)) {
            SessionSubscribeResult.CursorExpired -> exchange.respondError(
                ErrorCode.CURSOR_EXPIRED, "cursor is older than retained session history", status = 409,
            )
            is SessionSubscribeResult.Subscribed -> {
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
                            is SessionDelivery.Patch -> output.write(
                                "data: ${
                                    Json.write(
                                        mapOf(
                                            "type" to "patch", "revision" to delivery.patch.revision,
                                            "changed" to delivery.patch.changed.mapValues { (_, p) -> mapOf("x" to p.x, "y" to p.y) },
                                            "removed" to delivery.patch.removed.toList(),
                                        ),
                                    )
                                }\n\n",
                            )
                            is SessionDelivery.Gap -> {
                                output.write("data: ${Json.write(mapOf("type" to "gap", "resync" to true))}\n\n")
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

    /**
     * `GET /api/presence?clientId=ID` (SSE): a lightweight, non-deterministic side channel reporting
     * which clients currently have a connection open. This never touches [SimulationRuntime] or the
     * event hub -- presence is connection liveness, not simulation state (see [PresenceRegistry]).
     * Pushes `{"type":"roster","clients":[...]}` on connect and whenever the roster changes.
     */
    private fun handlePresence(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return exchange.respond(405, "text/plain", "method not allowed")
        val clientId = query(exchange)["clientId"]
        if (clientId.isNullOrBlank()) return exchange.respond(400, "text/plain", "clientId is required")

        presence.join(clientId)
        val latest = java.util.concurrent.atomic.AtomicReference(presence.current())
        val unsubscribe = presence.onChange { latest.set(it) }
        exchange.responseHeaders.set("Content-Type", "text/event-stream")
        exchange.responseHeaders.set("Cache-Control", "no-cache")
        exchange.responseHeaders.set("X-Accel-Buffering", "no")
        exchange.sendResponseHeaders(200, 0)
        val output = exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8)
        var lastSent: Set<String>? = null
        try {
            output.write(": connected\n\n")
            output.flush()
            while (!Thread.currentThread().isInterrupted) {
                val roster = latest.get()
                // An unchanged roster still writes a ping comment, not just a real update: an idle
                // connection otherwise never attempts a write, so a client that vanished without a
                // clean close (closed tab, network drop) would never be noticed and never leave the
                // roster. Pinging means a broken pipe surfaces as an IOException within one interval.
                if (roster != lastSent) {
                    output.write("data: ${Json.write(mapOf("type" to "roster", "clients" to roster.sorted()))}\n\n")
                    lastSent = roster
                } else {
                    output.write(": ping\n\n")
                }
                output.flush()
                Thread.sleep(PRESENCE_POLL_INTERVAL_MS)
            }
        } catch (_: IOException) {
            // Browser disconnected.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            unsubscribe()
            presence.leave(clientId)
            exchange.close()
        }
    }

    private fun HttpExchange.respondError(code: ErrorCode, message: String, status: Int = 200) =
        respond(status, "application/json", Json.write(mapOf("type" to "error", "error" to mapOf("code" to code.name, "message" to message))))

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

    private companion object {
        const val PRESENCE_POLL_INTERVAL_MS = 50L
    }
}
