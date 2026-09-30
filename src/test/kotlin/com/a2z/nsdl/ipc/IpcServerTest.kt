package com.a2z.nsdl.ipc

import com.a2z.nsdl.Composition
import com.a2z.nsdl.app.TypeRegistry
import com.a2z.nsdl.app.types.Cat5CableType
import com.a2z.nsdl.app.types.DhcpServerHostType
import com.a2z.nsdl.app.types.PrinterType
import com.a2z.nsdl.events.EventHub
import com.a2z.nsdl.runtime.SimulationRuntime
import com.a2z.nsdl.sim.VirtualScheduler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IpcServerTest {
    private lateinit var composition: Composition
    private lateinit var server: IpcServer
    private val clients = mutableListOf<IpcClient>()

    private fun start() {
        composition = Composition(randomSeed = 0L)
        server = IpcServer(composition.runtime, port = 0)
        server.start()
    }

    private fun newClient() = IpcClient("127.0.0.1", server.port).also { clients += it }

    @AfterEach
    fun tearDown() {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        if (::server.isInitialized) server.stop()
        if (::composition.isInitialized) composition.close()
    }

    @Test
    fun `malformed JSON gets a structured error reply with no id`() {
        start()
        val client = newClient()
        client.sendRawLine("{not json")

        val reply = client.nextPushed()
        assertNotNull(reply)
        assertEquals("error", reply!!["type"])
        assertNull(reply["id"])
        assertEquals("INVALID_REQUEST", (reply["error"] as Map<*, *>)["code"])
    }

    @Test
    fun `an unsupported protocol version is rejected`() {
        start()
        val client = newClient()
        client.sendRawLine("""{"v":2,"id":"r1","op":"listTypes","params":{}}""")

        val reply = client.nextPushed()
        assertEquals("error", reply!!["type"])
        assertEquals("r1", reply["id"])
        assertEquals("UNSUPPORTED_VERSION", (reply["error"] as Map<*, *>)["code"])
    }

    @Test
    fun `an unknown op is rejected`() {
        start()
        val client = newClient()
        val reply = client.request("frobnicate")
        assertEquals("error", reply["type"])
        assertEquals("UNKNOWN_OP", (reply["error"] as Map<*, *>)["code"])
    }

    @Test
    fun `create, connect and inspect round trip over the wire`() {
        start()
        val client = newClient()

        val created = client.request("create", mapOf("id" to "printer1", "type" to "printer"))
        assertEquals("result", created["type"])
        assertEquals("printer1", (created["data"] as Map<*, *>)["id"])

        client.request("create", mapOf("id" to "server1", "type" to "gateway", "props" to mapOf("address" to "10.0.0.1", "poolStart" to "10.0.0.100", "poolEnd" to "10.0.0.110")))
        client.request("create", mapOf("id" to "cable1", "type" to "cat5-cable"))

        val connected = client.request("connect", mapOf("cableId" to "cable1", "a" to "printer1.eth0", "b" to "server1.eth0"))
        assertEquals("result", connected["type"])
        assertEquals(true, connected["changed"])

        val inspected = client.request("inspect", mapOf("id" to "printer1"))
        assertEquals("result", inspected["type"])
        assertEquals("printer1", (inspected["data"] as Map<*, *>)["id"])
    }

    @Test
    fun `a rejected command returns a structured error with code, message and details`() {
        start()
        val client = newClient()
        client.request("create", mapOf("id" to "printer1", "type" to "printer"))

        val reply = client.request("create", mapOf("id" to "printer1", "type" to "printer"))
        assertEquals("error", reply["type"])
        val error = reply["error"] as Map<*, *>
        assertEquals("DUPLICATE_ID", error["code"])
        assertTrue((error["message"] as String).isNotBlank())
    }

    @Test
    fun `a filtered subscription only streams matching events`() {
        start()
        val client = newClient()
        val sub = client.request("subscribe", mapOf("types" to listOf("ObjectCreated"), "capacity" to 100L))
        val subId = (sub["data"] as Map<*, *>)["subscriptionId"] as String

        client.request("create", mapOf("id" to "cable1", "type" to "cat5-cable"))
        client.request("powerOn", mapOf("id" to "cable1")) // NOT_POWERABLE: rejected, no event

        val pushed = client.nextPushed()
        assertNotNull(pushed)
        assertEquals("event", pushed!!["type"])
        assertEquals(subId, pushed["subscriptionId"])
        assertEquals("ObjectCreated", pushed["eventType"])
        assertNull(client.nextPushed(timeoutMs = 300), "the rejected powerOn produced no event to filter through")
    }

    @Test
    fun `subscribing with a cursor older than the retained ring is rejected as CURSOR_EXPIRED`() {
        val runtime = SimulationRuntime(VirtualScheduler(), EventHub(retention = 2, now = { 0L }), TypeRegistry().apply { register(Cat5CableType) })
        server = IpcServer(runtime, port = 0)
        server.start()
        val client = newClient()
        repeat(5) { client.request("create", mapOf("id" to "cable$it", "type" to "cat5-cable")) }

        val reply = client.request("subscribe", mapOf("from" to 0L, "capacity" to 100L))
        assertEquals("error", reply["type"])
        assertEquals("CURSOR_EXPIRED", (reply["error"] as Map<*, *>)["code"])
    }

    @Test
    fun `unsubscribe stops further events for that subscription`() {
        start()
        val client = newClient()
        val sub = client.request("subscribe", mapOf("types" to listOf("ObjectCreated"), "capacity" to 100L))
        val subId = (sub["data"] as Map<*, *>)["subscriptionId"] as String

        val unsub = client.request("unsubscribe", mapOf("subscriptionId" to subId))
        assertEquals("result", unsub["type"])

        client.request("create", mapOf("id" to "cable1", "type" to "cat5-cable"))
        assertNull(client.nextPushed(timeoutMs = 300), "no events after unsubscribing")
    }

    @Test
    fun `each client only sees its own replies, and one client's traffic does not affect another`() {
        start()
        val a = newClient()
        val b = newClient()

        val bSub = b.request("subscribe", mapOf("types" to listOf("ObjectCreated"), "capacity" to 100L))
        val bSubId = (bSub["data"] as Map<*, *>)["subscriptionId"] as String

        val aResult = a.request("create", mapOf("id" to "printer1", "type" to "printer"), id = "shared-id")
        assertEquals("printer1", (aResult["data"] as Map<*, *>)["id"], "a got its own reply for its own request id")

        val event = b.nextPushed()
        assertNotNull(event)
        assertEquals(bSubId, event!!["subscriptionId"], "b's subscription saw a's creation")

        b.close()
        // a is unaffected by b's disconnect.
        val stillWorks = a.request("inspect", mapOf("id" to "printer1"))
        assertEquals("result", stillWorks["type"])
    }
}
