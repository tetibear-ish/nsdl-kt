package com.a2z.nsdl.web

import com.a2z.nsdl.Composition
import com.a2z.nsdl.app.TypeRegistry
import com.a2z.nsdl.events.EventHub
import com.a2z.nsdl.ipc.json.Json
import com.a2z.nsdl.runtime.SimulationRuntime
import com.a2z.nsdl.sim.VirtualScheduler
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HttpSimulationServerTest {
    private val composition = Composition(randomSeed = 7L)
    private val server = HttpSimulationServer(composition.runtime, port = 0)

    @AfterEach
    fun tearDown() {
        server.stop()
        composition.close()
    }

    @Test
    fun `browser client can load the app submit commands and receive SSE events`() {
        server.start()
        val base = "http://127.0.0.1:${server.port}"
        val http = HttpClient.newHttpClient()

        val page = http.send(
            HttpRequest.newBuilder(URI("$base/")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("NSDL Network Lab"))
        assertTrue(page.body().contains("remote.js"))

        val events = URI("$base/api/events?from=0").toURL().openConnection() as HttpURLConnection
        events.readTimeout = 2_000
        val eventReader = events.inputStream.bufferedReader()
        assertEquals(200, events.responseCode)
        assertTrue(eventReader.readLine().startsWith(":"), "SSE stream should send an initial comment")
        eventReader.readLine()

        val reply = post(http, "$base/api/command", """{"v":1,"id":"web-1","op":"create","params":{"id":"printer1","type":"printer"}}""")
        assertEquals(200, reply.statusCode())
        @Suppress("UNCHECKED_CAST")
        val decoded = Json.parse(reply.body()) as Map<String, Any?>
        assertEquals("result", decoded["type"])
        assertEquals("printer1", (decoded["data"] as Map<*, *>)["id"])

        assertTrue(eventReader.readLine().startsWith("data:"), "created object should be pushed over SSE")
        events.disconnect()
    }

    @Test
    fun `a move is applied and the resulting position is visible in the session snapshot`() {
        server.start()
        val base = "http://127.0.0.1:${server.port}"
        val http = HttpClient.newHttpClient()

        val reply = post(http, "$base/api/move", """{"id":"printer1","x":12.5,"y":7.0,"clientId":"c1","requestId":"r1"}""")

        assertEquals(200, reply.statusCode())
        @Suppress("UNCHECKED_CAST")
        val decoded = Json.parse(reply.body()) as Map<String, Any?>
        assertEquals("applied", decoded["type"])
        assertEquals(1L, decoded["revision"])

        val snapshot = http.send(HttpRequest.newBuilder(URI("$base/api/session/snapshot")).GET().build(), HttpResponse.BodyHandlers.ofString())
        @Suppress("UNCHECKED_CAST")
        val snapshotBody = Json.parse(snapshot.body()) as Map<String, Any?>
        assertEquals(1L, snapshotBody["revision"])
        @Suppress("UNCHECKED_CAST")
        val positions = snapshotBody["positions"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val printer1 = positions["printer1"] as Map<String, Any?>
        assertEquals(12.5, printer1["x"])
        assertEquals(7.0, printer1["y"])
    }

    @Test
    fun `a move citing a stale baseRevision is rejected as a conflict carrying the authoritative position`() {
        server.start()
        val base = "http://127.0.0.1:${server.port}"
        val http = HttpClient.newHttpClient()
        post(http, "$base/api/move", """{"id":"n1","x":1.0,"y":1.0,"clientId":"c1","requestId":"r1"}""") // revision 1

        val reply = post(http, "$base/api/move", """{"id":"n1","x":9.0,"y":9.0,"clientId":"c2","requestId":"r2","baseRevision":0}""")

        assertEquals(200, reply.statusCode())
        @Suppress("UNCHECKED_CAST")
        val decoded = Json.parse(reply.body()) as Map<String, Any?>
        assertEquals("conflict", decoded["type"])
        @Suppress("UNCHECKED_CAST")
        val current = decoded["current"] as Map<String, Any?>
        assertEquals(1.0, current["x"])
        assertEquals(1.0, current["y"])
    }

    @Test
    fun `reusing the same client's request id with a different payload is rejected as IDEMPOTENCY_CONFLICT`() {
        server.start()
        val base = "http://127.0.0.1:${server.port}"
        val http = HttpClient.newHttpClient()
        post(http, "$base/api/move", """{"id":"n1","x":1.0,"y":1.0,"clientId":"c1","requestId":"r1"}""")

        val reply = post(http, "$base/api/move", """{"id":"n1","x":2.0,"y":2.0,"clientId":"c1","requestId":"r1"}""")

        @Suppress("UNCHECKED_CAST")
        val decoded = Json.parse(reply.body()) as Map<String, Any?>
        assertEquals("error", decoded["type"])
        @Suppress("UNCHECKED_CAST")
        val error = decoded["error"] as Map<String, Any?>
        assertEquals("IDEMPOTENCY_CONFLICT", error["code"])
    }

    @Test
    fun `a move missing required fields is rejected as INVALID_REQUEST`() {
        server.start()
        val base = "http://127.0.0.1:${server.port}"
        val http = HttpClient.newHttpClient()

        val reply = post(http, "$base/api/move", """{"id":"n1"}""")

        @Suppress("UNCHECKED_CAST")
        val decoded = Json.parse(reply.body()) as Map<String, Any?>
        assertEquals("error", decoded["type"])
        @Suppress("UNCHECKED_CAST")
        val error = decoded["error"] as Map<String, Any?>
        assertEquals("INVALID_REQUEST", error["code"])
    }

    @Test
    fun `a session stream from an earlier revision replays later moves, and reconnecting from the new revision sees nothing stale`() {
        server.start()
        val base = "http://127.0.0.1:${server.port}"
        val http = HttpClient.newHttpClient()
        post(http, "$base/api/move", """{"id":"n1","x":1.0,"y":1.0,"clientId":"c1","requestId":"r1"}""") // revision 1

        val stream = URI("$base/api/session/stream?from=0").toURL().openConnection() as HttpURLConnection
        stream.readTimeout = 2_000
        val reader = stream.inputStream.bufferedReader()
        assertEquals(200, stream.responseCode)
        assertTrue(reader.readLine().startsWith(":"))
        reader.readLine()

        val line = reader.readLine()
        assertTrue(line.startsWith("data:"), "the already-applied move is replayed to a late subscriber")
        assertTrue(line.contains("\"n1\""))
        stream.disconnect()

        // Reconnecting from the revision it just saw must not replay the same move again.
        post(http, "$base/api/move", """{"id":"n2","x":2.0,"y":2.0,"clientId":"c1","requestId":"r2"}""") // revision 2
        val resumed = URI("$base/api/session/stream?from=1").toURL().openConnection() as HttpURLConnection
        resumed.readTimeout = 2_000
        val resumedReader = resumed.inputStream.bufferedReader()
        resumedReader.readLine()
        resumedReader.readLine()
        val resumedLine = resumedReader.readLine()
        assertTrue(resumedLine.contains("\"n2\""), "only the move after the cursor is replayed")
        assertFalse(resumedLine.contains("\"n1\""))
        resumed.disconnect()
    }

    @Test
    fun `a session stream cursor older than the retained window is rejected as CURSOR_EXPIRED`() {
        // A dedicated tiny-retention runtime so eviction is reachable with a handful of moves,
        // instead of needing thousands of HTTP round trips against the default retention.
        val smallRuntime = SimulationRuntime(VirtualScheduler(), EventHub(now = { 0L }), TypeRegistry(), collabRetention = 2)
        val smallServer = HttpSimulationServer(smallRuntime, port = 0)
        try {
            smallServer.start()
            val base = "http://127.0.0.1:${smallServer.port}"
            val http = HttpClient.newHttpClient()
            post(http, "$base/api/move", """{"id":"a","x":0.0,"y":0.0,"clientId":"c1","requestId":"r1"}""") // revision 1, evicted
            post(http, "$base/api/move", """{"id":"b","x":0.0,"y":0.0,"clientId":"c1","requestId":"r2"}""") // revision 2
            post(http, "$base/api/move", """{"id":"c","x":0.0,"y":0.0,"clientId":"c1","requestId":"r3"}""") // revision 3 -- ring holds [2, 3]

            val connection = URI("$base/api/session/stream?from=0").toURL().openConnection() as HttpURLConnection
            connection.readTimeout = 2_000
            assertEquals(409, connection.responseCode)
            val body = connection.errorStream.bufferedReader().readText()
            @Suppress("UNCHECKED_CAST")
            val decoded = Json.parse(body) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val error = decoded["error"] as Map<String, Any?>
            assertEquals("CURSOR_EXPIRED", error["code"])
        } finally {
            smallServer.stop()
            smallRuntime.close()
        }
    }

    @Test
    fun `presence reports a connected client, and reports it gone once it disconnects`() {
        server.start()
        val base = "http://127.0.0.1:${server.port}"

        val presence = URI("$base/api/presence?clientId=alice").toURL().openConnection() as HttpURLConnection
        presence.readTimeout = 3_000
        val reader = presence.inputStream.bufferedReader()
        assertEquals(200, presence.responseCode)
        assertTrue(reader.readLine().startsWith(":"), "an SSE comment confirms the connection")

        val roster = nextRosterLine(reader)
        assertTrue(roster.contains("alice"), "the initial roster includes the connecting client itself")

        val second = URI("$base/api/presence?clientId=bob").toURL().openConnection() as HttpURLConnection
        second.readTimeout = 3_000
        second.inputStream.bufferedReader() // establish bob's own connection; its content is not asserted here

        val updated = nextRosterLine(reader)
        assertTrue(updated.contains("bob"), "alice's stream is notified when bob joins")

        second.disconnect()

        // Bob's idle stream still pings periodically, so its next write discovers the broken pipe
        // and leaves the roster -- departure is noticed without alice's own connection changing at all.
        val afterDeparture = nextRosterLine(reader)
        assertFalse(afterDeparture.contains("bob"), "alice's stream is notified once bob disconnects")
        assertTrue(afterDeparture.contains("alice"))

        presence.disconnect()
    }

    /** Reads lines from an SSE stream until a `data:` line (skipping blank lines and `: ping` comments). */
    private fun nextRosterLine(reader: java.io.BufferedReader): String {
        while (true) {
            val line = reader.readLine() ?: throw AssertionError("presence stream closed unexpectedly")
            if (line.startsWith("data:")) return line
        }
    }

    private fun post(client: HttpClient, url: String, body: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )
}
