package com.a2z.nsdl

import com.a2z.nsdl.ipc.IpcClient
import java.io.BufferedReader
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProcessBoundaryTest {
    @Test
    fun `serve exposes a complete simulation across a process boundary`() {
        val classpath = System.getProperty("test.runtime.classpath")
        val process = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            classpath,
            "com.a2z.nsdl.MainKt",
            "serve",
            "--port",
            "0",
            "--seed",
            "7",
        ).redirectErrorStream(true).start()

        try {
            val reader = process.inputStream.bufferedReader()
            val listening = readLineWithin(reader, 5_000)
            assertNotNull(listening, "server did not announce its port")
            assertTrue(listening!!.startsWith("NSDL_IPC_LISTENING port="), listening)
            val port = listening.substringAfter('=').toInt()

            IpcClient("127.0.0.1", port).use { client ->
                assertResult(client.request("create", mapOf("id" to "printer", "type" to "printer")))
                assertResult(client.request("create", mapOf(
                    "id" to "server", "type" to "dhcp-server-host",
                    "props" to mapOf("address" to "10.0.0.1", "poolStart" to "10.0.0.100", "poolEnd" to "10.0.0.110"),
                )))
                assertResult(client.request("create", mapOf("id" to "cable", "type" to "cat5-cable")))
                assertEquals(true, client.request("connect", mapOf(
                    "cableId" to "cable", "a" to "printer.eth0", "b" to "server.eth0",
                ))["changed"])
                assertResult(client.request("subscribe", mapOf("objectId" to "printer", "from" to 0L, "capacity" to 100L)))
                assertResult(client.request("powerOn", mapOf("id" to "server")))
                assertResult(client.request("powerOn", mapOf("id" to "printer")))
                assertResult(client.request("advance", mapOf("durationMs" to 5_000L)))

                val inspected = client.request("inspect", mapOf("id" to "printer.eth0"))
                val state = (inspected["data"] as Map<*, *>)["state"] as Map<*, *>
                val ipv4 = state["ipv4"] as Map<*, *>
                assertEquals("10.0.0.100", ipv4["address"])
                assertNotNull(client.nextPushed(2_000), "the subscription should stream events")

                assertEquals(true, client.request("disconnect", mapOf("cableId" to "cable"))["changed"])
                assertEquals(false, client.request("disconnect", mapOf("cableId" to "cable"))["changed"])
            }
        } finally {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
        }
    }

    private fun assertResult(reply: Map<String, Any?>) = assertEquals("result", reply["type"], reply.toString())

    private fun readLineWithin(reader: BufferedReader, timeoutMs: Long): String? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (reader.ready()) return reader.readLine()
            Thread.sleep(10)
        }
        return null
    }
}
