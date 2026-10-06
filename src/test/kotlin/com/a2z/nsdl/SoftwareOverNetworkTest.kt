package com.a2z.nsdl

import com.a2z.nsdl.app.Command
import com.a2z.nsdl.app.CommandResult
import com.a2z.nsdl.app.EndpointRef
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.runtime.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * End to end through the command API: software on a workstation reaches other machines purely by
 * name. Underneath, DHCP announces each host's name, the gateway's DNS learns it, the workstation
 * resolves it, and ARP finds the hardware address -- none of which the applications are told about.
 */
class SoftwareOverNetworkTest {
    private val composition = Composition(randomSeed = 7)

    @AfterEach
    fun close() = composition.close()

    private fun run(command: Command): Any? {
        val result = composition.runtime.submit(Request(command)).result
        assertTrue(result is CommandResult.Ok, "$command -> $result")
        return (result as CommandResult.Ok).data
    }

    private fun advance(duration: Duration) = run(Command.Advance(duration))

    @Suppress("UNCHECKED_CAST")
    private fun state(id: String) = (run(Command.Inspect(id)) as ObjectSnapshot).state

    @Suppress("UNCHECKED_CAST")
    private fun invoke(id: String, action: String, params: Map<String, Any?>) =
        run(Command.Invoke(id, action, params)) as Map<String, Any?>

    private fun office() {
        val hosts = listOf("gateway" to "gateway", "switch" to "ethernet-switch", "pc1" to "computer", "printer1" to "printer", "intranet" to "web-server", "lab1" to "linux-host")
        hosts.forEach { (id, type) -> run(Command.Create(id, type)) }
        listOf("gateway.eth0", "pc1.eth0", "printer1.eth0", "intranet.eth0", "lab1.eth0").forEachIndexed { i, endpoint ->
            run(Command.Create("cable$i", "cat5-cable"))
            run(Command.Connect("cable$i", EndpointRef(endpoint), EndpointRef("switch.port${i + 1}")))
        }
        hosts.forEach { (id, _) -> run(Command.PowerOn(id)) }
        advance(10.seconds) // boot + DHCP for everyone
    }

    @Test
    fun `hosts announce their names over DHCP and the gateway's DNS answers for them`() {
        office()

        @Suppress("UNCHECKED_CAST")
        val records = (state("gateway.dns-server")["records"] as List<Map<String, Any?>>).associate { it["name"] to it["source"] }

        assertEquals(mapOf("gateway" to "STATIC", "pc1" to "DHCP", "printer1" to "DHCP", "intranet" to "DHCP", "lab1" to "DHCP"), records)
    }

    @Test
    fun `the browser opens a page by host name`() {
        office()

        assertEquals(true, invoke("pc1.browser", "open", mapOf("url" to "http://intranet/about"))["accepted"])
        advance(1.seconds)

        val browser = state("pc1.browser")
        assertEquals("LOADED", browser["status"])
        @Suppress("UNCHECKED_CAST")
        val page = browser["page"] as Map<String, Any?>
        assertEquals(200, page["status"])
        assertEquals("About intranet", page["title"])
    }

    @Test
    fun `printing a document by printer name reaches the printer`() {
        office()

        invoke("pc1.print-spooler", "print", mapOf("document" to "minutes.txt", "printer" to "printer1", "pages" to 2))
        advance(1.seconds)

        @Suppress("UNCHECKED_CAST")
        val job = (state("pc1.print-spooler")["jobs"] as List<Map<String, Any?>>).single()
        assertEquals("ACCEPTED", job["status"], "job: $job")
        assertEquals("accepted by printer1", job["detail"])
        @Suppress("UNCHECKED_CAST")
        val received = (state("printer1.print-server")["completedJobs"] as List<Map<String, Any?>>).single()
        assertEquals("pc1/job-1", received["id"], "the printer records the job under the spooler's name")
        assertEquals("minutes.txt", received["document"])
    }

    private fun address(id: String) = (state("$id.eth0")["ipv4"] as Map<*, *>)["address"]

    @Test
    fun `printing to a switched-off printer times out, naming the unanswered ARP request`() {
        office()
        val printerAddress = address("printer1")
        run(Command.PowerOff("printer1"))

        invoke("pc1.print-spooler", "print", mapOf("document" to "minutes.txt", "printer" to "printer1"))
        advance(15.seconds)

        @Suppress("UNCHECKED_CAST")
        val job = (state("pc1.print-spooler")["jobs"] as List<Map<String, Any?>>).single()
        assertEquals("FAILED", job["status"])
        assertEquals("printing minutes.txt on printer1 timed out: no ARP reply from $printerAddress", job["detail"])
    }

    @Test
    fun `with its own cable unplugged, the computer says so at once instead of blaming DNS`() {
        office()
        run(Command.Disconnect("cable1"))

        invoke("pc1.print-spooler", "print", mapOf("document" to "minutes.txt", "printer" to "printer1"))

        @Suppress("UNCHECKED_CAST")
        val job = (state("pc1.print-spooler")["jobs"] as List<Map<String, Any?>>).single()
        assertEquals("can't find printer printer1: the network link is down", job["detail"], "no simulated time had to pass")
    }

    @Test
    fun `the terminal runs a command on another host over ssh`() {
        office()

        invoke("pc1.terminal", "ssh", mapOf("target" to "student@lab1", "password" to "hunter2", "command" to "whoami"))
        advance(1.seconds)

        assertEquals(listOf("$ ssh student@lab1 whoami", "student"), state("pc1.terminal")["transcript"])
    }

    @Test
    fun `a switched-off server shows up to the user as an application error`() {
        office()
        val serverAddress = address("intranet")
        run(Command.PowerOff("intranet"))

        invoke("pc1.browser", "open", mapOf("url" to "intranet"))
        advance(15.seconds)

        val browser = state("pc1.browser")
        assertEquals("ERROR", browser["status"])
        // Without DHCP RELEASE its lease, and so its DNS name, outlive it: the name resolves, nobody answers ARP.
        assertEquals("loading http://intranet/ timed out: no ARP reply from $serverAddress", browser["error"])
    }

    @Test
    fun `software state is volatile across a power cycle`() {
        office()
        invoke("pc1.browser", "open", mapOf("url" to "intranet"))
        advance(1.seconds)

        run(Command.PowerOff("pc1"))
        run(Command.PowerOn("pc1"))
        advance(10.seconds)

        assertEquals("IDLE", state("pc1.browser")["status"])
        assertEquals(true, state("pc1.os")["running"])
    }
}
