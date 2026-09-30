package com.a2z.nsdl.web

import com.a2z.nsdl.Composition
import com.a2z.nsdl.ipc.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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

    private fun post(client: HttpClient, url: String, body: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )
}
