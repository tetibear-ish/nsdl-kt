package com.a2z.nsdl.ipc

import com.a2z.nsdl.app.CommandError
import com.a2z.nsdl.app.ErrorCode
import com.a2z.nsdl.events.EventRecord
import com.a2z.nsdl.ipc.json.Json
import com.a2z.nsdl.model.EventPayload.BootCompleted
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReplyCodecTest {
    @Suppress("UNCHECKED_CAST")
    private fun decode(line: String) = Json.parse(line) as Map<String, Any?>

    @Test
    fun `encodes a result reply with its revision`() {
        val line = ReplyCodec.encodeResult(id = "r1", revision = 5L, changed = true, data = mapOf("truncated" to false))
        assertEquals(
            mapOf("type" to "result", "id" to "r1", "revision" to 5L, "changed" to true, "data" to mapOf("truncated" to false)),
            decode(line),
        )
    }

    @Test
    fun `encodes an object snapshot's state and relations as wire-safe values`() {
        val snapshot = ObjectSnapshot(
            ObjectId("printer1"), "printer", ObjectKind.DEVICE,
            state = mapOf("power" to "OFF"),
            relations = mapOf("interfaces" to listOf(ObjectId("printer1.eth0"))),
        )
        val line = ReplyCodec.encodeResult(id = "r1", revision = 1L, changed = true, data = snapshot)
        assertEquals(
            mapOf(
                "type" to "result", "id" to "r1", "revision" to 1L, "changed" to true,
                "data" to mapOf(
                    "id" to "printer1", "type" to "printer", "kind" to "DEVICE",
                    "state" to mapOf("power" to "OFF"),
                    "relations" to mapOf("interfaces" to listOf("printer1.eth0")),
                ),
            ),
            decode(line),
        )
    }

    @Test
    fun `encodes a list of snapshots`() {
        val snap = ObjectSnapshot(ObjectId("a"), "printer", ObjectKind.DEVICE, emptyMap())
        val line = ReplyCodec.encodeResult(id = "r1", revision = 1L, changed = true, data = listOf(snap))
        val data = decode(line)["data"] as List<*>
        assertEquals("a", (data[0] as Map<*, *>)["id"])
    }

    @Test
    fun `encodes an error reply with code, message and details`() {
        val line = ReplyCodec.encodeError(id = "r1", error = CommandError(ErrorCode.UNKNOWN_TYPE, "unknown type 'x'", mapOf("type" to "x")))
        assertEquals(
            mapOf(
                "type" to "error", "id" to "r1",
                "error" to mapOf("code" to "UNKNOWN_TYPE", "message" to "unknown type 'x'", "details" to mapOf("type" to "x")),
            ),
            decode(line),
        )
    }

    @Test
    fun `encodes a pushed event with its subscription id`() {
        val record = EventRecord(seq = 7L, timeMs = 3000L, source = ObjectId("printer1"), type = "BootCompleted", payload = BootCompleted(1))
        val line = ReplyCodec.encodeEvent("sub-1", record)
        assertEquals(
            mapOf(
                "type" to "event", "subscriptionId" to "sub-1", "seq" to 7L, "timeMs" to 3000L,
                "source" to "printer1", "eventType" to "BootCompleted", "correlationId" to null,
                "data" to mapOf("generation" to 1L),
            ),
            decode(line),
        )
    }

    @Test
    fun `encodes a gap with resync true`() {
        val line = ReplyCodec.encodeGap("sub-1")
        assertEquals(mapOf("type" to "gap", "subscriptionId" to "sub-1", "resync" to true), decode(line))
    }
}
