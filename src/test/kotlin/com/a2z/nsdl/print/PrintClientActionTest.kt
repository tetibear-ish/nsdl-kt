package com.a2z.nsdl.print

import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.sim.WorkScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [PrintClient] is Actionable so a scenario (or, eventually, a UI action) can trigger a job submission
 * through [com.a2z.nsdl.app.Command.Invoke] without a dedicated print-specific command.
 */
class PrintClientActionTest {
    private val printerAddress = Ipv4Address.parse("192.168.1.20")
    private val printerMac = MacAddress.local(2)

    private fun startedClient(): Pair<PrintClient, RecordingUdpTransport> {
        val transport = RecordingUdpTransport()
        val client = PrintClient(ObjectId("computer1.print-client"), transport)
        client.start(WorkScope(VirtualScheduler()))
        return client to transport
    }

    @Test
    fun `the submit action forwards its params to submit and reports the resulting job`() {
        val (client, transport) = startedClient()

        val outcome = client.perform(
            "submit",
            mapOf(
                "jobId" to "job-1",
                "documentName" to "notes.txt",
                "bytes" to 2_500,
                "printerAddress" to printerAddress,
                "printerMac" to printerMac,
            ),
        )

        assertTrue(outcome.accepted)
        assertEquals("job-1", client.jobs.single().id)
        assertEquals(5, transport.sent.size, "START + three 1000-byte chunks (the last one partial) + COMPLETE")
    }

    @Test
    fun `an unknown action is rejected without touching any job`() {
        val (client, _) = startedClient()

        val outcome = client.perform("dance", emptyMap())

        assertTrue(!outcome.accepted)
        assertTrue(client.jobs.isEmpty())
    }

    @Test
    fun `missing required params reject the action instead of throwing`() {
        val (client, _) = startedClient()

        val outcome = client.perform("submit", mapOf("jobId" to "job-1"))

        assertTrue(!outcome.accepted)
        assertTrue(client.jobs.isEmpty())
    }
}
