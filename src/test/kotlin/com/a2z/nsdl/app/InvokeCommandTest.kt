package com.a2z.nsdl.app

import com.a2z.nsdl.model.ActionOutcome
import com.a2z.nsdl.model.Actionable
import com.a2z.nsdl.model.Inspectable
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** A component that records the action it was asked to perform, standing in for a real protocol service. */
private class RecordingActionable(override val id: ObjectId) : Inspectable, Actionable {
    var lastAction: String? = null
    var lastParams: Map<String, Any?> = emptyMap()

    override fun perform(action: String, params: Map<String, Any?>): ActionOutcome {
        lastAction = action
        lastParams = params
        return when (action) {
            "ping" -> ActionOutcome(accepted = true, detail = "pong", data = mapOf("echo" to params["value"]))
            else -> ActionOutcome(accepted = false, detail = "unknown action '$action'")
        }
    }

    override fun snapshot() = ObjectSnapshot(id, "actionable-probe", ObjectKind.PROTOCOL, emptyMap())
}

/** A device whose only component is a [RecordingActionable], reachable at "host.probe". */
private object ActionableHostType : ObjectType {
    override val schema = ObjectTypeSchema("actionable-host", ObjectKind.DEVICE, properties = emptyList())

    override fun create(id: ObjectId, props: Map<String, Any?>, ctx: CreationContext): SimObject {
        val probe = RecordingActionable(id.child("probe"))
        val root = object : Inspectable {
            override val id = id
            override fun snapshot() = ObjectSnapshot(id, schema.name, ObjectKind.DEVICE, emptyMap())
        }
        return SimObject(root = root, components = listOf(probe))
    }
}

class InvokeCommandTest {
    private val scheduler = VirtualScheduler()
    private val sink = RecordingSink()
    private val registry = TypeRegistry().apply { register(ActionableHostType) }
    private lateinit var service: SimulationService

    @BeforeEach
    fun setUp() {
        service = SimulationService(scheduler, sink, registry)
        service.handle(Command.Create("host1", "actionable-host"))
    }

    private fun ok(result: CommandResult): CommandResult.Ok {
        assertTrue(result is CommandResult.Ok, "expected Ok, got $result")
        return result as CommandResult.Ok
    }

    private fun rejected(result: CommandResult): CommandError {
        assertTrue(result is CommandResult.Rejected, "expected Rejected, got $result")
        return (result as CommandResult.Rejected).error
    }

    @Test
    fun `invoking a supported action on an Actionable component forwards action and params and returns its outcome`() {
        val result = ok(service.handle(Command.Invoke("host1.probe", "ping", mapOf("value" to "hi"))))

        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any?>
        assertTrue(result.changed)
        assertEquals(true, data["accepted"])
        assertEquals("pong", data["detail"])
        assertEquals(mapOf("echo" to "hi"), data["data"])
    }

    @Test
    fun `an outcome with accepted=false is still Ok, but changed=false`() {
        val result = ok(service.handle(Command.Invoke("host1.probe", "unsupported", emptyMap())))
        assertFalse(result.changed)
        @Suppress("UNCHECKED_CAST")
        assertEquals(false, (result.data as Map<String, Any?>)["accepted"])
    }

    @Test
    fun `invoking on an unknown object is rejected as UNKNOWN_OBJECT`() {
        assertEquals(ErrorCode.UNKNOWN_OBJECT, rejected(service.handle(Command.Invoke("nope", "ping", emptyMap()))).code)
    }

    @Test
    fun `invoking on a component that is not Actionable is rejected as NOT_ACTIONABLE`() {
        assertEquals(ErrorCode.NOT_ACTIONABLE, rejected(service.handle(Command.Invoke("host1", "ping", emptyMap()))).code)
    }

    @Test
    fun `invoking with an invalid id is rejected as INVALID_ID`() {
        assertEquals(ErrorCode.INVALID_ID, rejected(service.handle(Command.Invoke("bad id!", "ping", emptyMap()))).code)
    }
}
