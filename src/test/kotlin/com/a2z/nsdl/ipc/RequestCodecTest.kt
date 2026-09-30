package com.a2z.nsdl.ipc

import com.a2z.nsdl.app.Command
import com.a2z.nsdl.app.EndpointRef
import com.a2z.nsdl.app.ErrorCode
import com.a2z.nsdl.events.EventFilter
import com.a2z.nsdl.model.ObjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class RequestCodecTest {
    @Test
    fun `malformed JSON is rejected as INVALID_REQUEST with no id`() {
        val result = RequestCodec.decode("{not json")
        assertTrue(result is DecodeResult.Failed)
        result as DecodeResult.Failed
        assertEquals(null, result.id)
        assertEquals(ErrorCode.INVALID_REQUEST, result.error.code)
    }

    @Test
    fun `a request whose version is not 1 is rejected as UNSUPPORTED_VERSION`() {
        val result = RequestCodec.decode("""{"v":2,"id":"r1","op":"listTypes","params":{}}""")
        assertTrue(result is DecodeResult.Failed)
        result as DecodeResult.Failed
        assertEquals("r1", result.id)
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, result.error.code)
    }

    @Test
    fun `an unrecognized op is rejected as UNKNOWN_OP`() {
        val result = RequestCodec.decode("""{"v":1,"id":"r1","op":"frobnicate","params":{}}""")
        assertTrue(result is DecodeResult.Failed)
        result as DecodeResult.Failed
        assertEquals("r1", result.id)
        assertEquals(ErrorCode.UNKNOWN_OP, result.error.code)
    }

    @Test
    fun `decodes listTypes and listObjects with no params`() {
        assertEquals(
            DecodeResult.Decoded("r1", IpcOperation.Run(Command.ListTypes)),
            RequestCodec.decode("""{"v":1,"id":"r1","op":"listTypes","params":{}}"""),
        )
        assertEquals(
            DecodeResult.Decoded("r2", IpcOperation.Run(Command.ListObjects)),
            RequestCodec.decode("""{"v":1,"id":"r2","op":"listObjects","params":{}}"""),
        )
    }

    @Test
    fun `decodes create with its id, type and props`() {
        val result = RequestCodec.decode("""{"v":1,"id":"r1","op":"create","params":{"id":"printer1","type":"printer","props":{"bootMs":500}}}""")
        assertEquals(
            DecodeResult.Decoded("r1", IpcOperation.Run(Command.Create("printer1", "printer", mapOf("bootMs" to 500L)))),
            result,
        )
    }

    @Test
    fun `decodes connect with two endpoint refs`() {
        val result = RequestCodec.decode("""{"v":1,"id":"r1","op":"connect","params":{"cableId":"cable1","a":"p.eth0","b":"s.eth0"}}""")
        assertEquals(
            DecodeResult.Decoded("r1", IpcOperation.Run(Command.Connect("cable1", EndpointRef("p.eth0"), EndpointRef("s.eth0")))),
            result,
        )
    }

    @Test
    fun `decodes advance with a duration in milliseconds`() {
        val result = RequestCodec.decode("""{"v":1,"id":"r1","op":"advance","params":{"durationMs":5000}}""")
        assertEquals(DecodeResult.Decoded("r1", IpcOperation.Run(Command.Advance(5000.milliseconds))), result)
    }

    @Test
    fun `decodes subscribe with an object and type filter, cursor and capacity`() {
        val result = RequestCodec.decode(
            """{"v":1,"id":"r1","op":"subscribe","params":{"objectId":"printer1","types":["BootCompleted"],"from":3,"capacity":100}}""",
        )
        assertEquals(
            DecodeResult.Decoded(
                "r1",
                IpcOperation.Subscribe(EventFilter(ObjectId("printer1"), setOf("BootCompleted")), from = 3, capacity = 100),
            ),
            result,
        )
    }

    @Test
    fun `decodes unsubscribe with a subscription id`() {
        val result = RequestCodec.decode("""{"v":1,"id":"r1","op":"unsubscribe","params":{"subscriptionId":"sub-1"}}""")
        assertEquals(DecodeResult.Decoded("r1", IpcOperation.Unsubscribe("sub-1")), result)
    }

    @Test
    fun `decodes applyTopology as a batch of Create then Connect commands`() {
        val result = RequestCodec.decode(
            """{"v":1,"id":"r1","op":"applyTopology","params":{
                "objects":[{"id":"printer1","type":"printer","props":{}}],
                "connections":[{"cableId":"cable1","a":"printer1.eth0","b":"server1.eth0"}]
            }}""",
        )
        assertEquals(
            DecodeResult.Decoded(
                "r1",
                IpcOperation.Run(
                    Command.ApplyTopology(
                        listOf(
                            Command.Create("printer1", "printer", emptyMap()),
                            Command.Connect("cable1", EndpointRef("printer1.eth0"), EndpointRef("server1.eth0")),
                        ),
                    ),
                ),
            ),
            result,
        )
    }

    @Test
    fun `create missing its required id param is rejected as INVALID_REQUEST`() {
        val result = RequestCodec.decode("""{"v":1,"id":"r1","op":"create","params":{"type":"printer"}}""")
        assertTrue(result is DecodeResult.Failed)
        assertEquals(ErrorCode.INVALID_REQUEST, (result as DecodeResult.Failed).error.code)
    }
}
