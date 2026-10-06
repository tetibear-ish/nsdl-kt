package com.a2z.nsdl.software

import com.a2z.nsdl.model.EventPayload.ApplicationEvent
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.sim.WorkScope
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The applications against a scripted [NetworkServices]: no packets, stacks or protocols involved,
 * which is the point of the software layer.
 */
class ApplicationsTest {
    /** Answers from tables; requests listed in [silent] are left pending forever. */
    private class FakeNetwork : NetworkServices {
        val hosts = mutableMapOf("intranet" to "10.0.0.5", "printer1" to "10.0.0.6", "lab1" to "10.0.0.7")
        val pages = mutableMapOf("/" to WebDocument(200, "OK", "Home", "Hello"))
        val silent = mutableSetOf<String>()
        val printed = mutableListOf<Pair<HostAddress, String>>()
        val diagnoses = mutableMapOf<HostAddress, String>()
        var held: (() -> Unit)? = null

        override fun resolve(name: String, done: (Outcome<HostAddress>) -> Unit) {
            if ("resolve" in silent) return
            done(hosts[name]?.let { Outcome.Success(HostAddress(it)) } ?: Outcome.Failure("no such host '$name'"))
        }

        override fun fetchPage(server: HostAddress, host: String, path: String, done: (Outcome<WebDocument>) -> Unit) {
            if ("fetch" in silent) return
            val answer = { done(Outcome.Success(pages[path] ?: WebDocument(404, "Not Found", "404 Not Found", ""))) }
            if ("hold" in silent) held = answer else answer()
        }

        override fun printDocument(printer: HostAddress, jobName: String, document: String, sizeBytes: Int, done: (Outcome<String>) -> Unit) {
            if ("print" in silent) return
            printed += printer to "$jobName:$document"
            done(Outcome.Success("job accepted"))
        }

        override fun runRemoteCommand(server: HostAddress, user: String, password: String, command: String, done: (Outcome<RemoteOutput>) -> Unit) {
            done(if (password == "hunter2") Outcome.Success(RemoteOutput("/home/$user", 0)) else Outcome.Failure("Permission denied (invalid credentials)"))
        }

        override fun diagnose(server: HostAddress) = diagnoses[server]
    }

    private val scheduler = VirtualScheduler()
    private val sink = RecordingSink()
    private val network = FakeNetwork()
    private val os = OperatingSystem(ObjectId("pc1"), network, sink, operationTimeout = 5.seconds)

    private fun boot() = os.boot(WorkScope(scheduler))

    @Test
    fun `applications are components of the host and refuse to act while it is off`() {
        assertEquals(listOf("pc1.browser", "pc1.print-spooler", "pc1.terminal"), os.applications.map { it.id.value })

        val outcome = os.browser.perform("open", mapOf("url" to "intranet"))

        assertFalse(outcome.accepted)
        assertEquals("the computer is off", outcome.detail)
    }

    @Test
    fun `the browser resolves the host name, then loads the page`() {
        boot()

        assertTrue(os.browser.perform("open", mapOf("url" to "http://intranet/")).accepted)

        assertEquals(WebBrowser.Status.LOADED, os.browser.status)
        assertEquals("Home", os.browser.page?.title)
        assertEquals(listOf("navigate", "loaded"), sink.of<ApplicationEvent>().map { it.activity })
    }

    @Test
    fun `a missing page is still a document, an unknown host is an error`() {
        boot()

        os.browser.perform("open", mapOf("url" to "intranet/nope"))
        assertEquals(404, os.browser.page?.status)

        os.browser.perform("open", mapOf("url" to "elsewhere"))
        assertEquals(WebBrowser.Status.ERROR, os.browser.status)
        assertEquals("can't find elsewhere: no such host 'elsewhere'", os.browser.error)
    }

    @Test
    fun `an operation the network never answers times out`() {
        boot()
        network.silent += "fetch"

        os.browser.perform("open", mapOf("url" to "intranet"))
        assertEquals(WebBrowser.Status.LOADING, os.browser.status)
        scheduler.advanceBy(5.seconds)

        assertEquals(WebBrowser.Status.ERROR, os.browser.status)
        assertEquals("loading http://intranet/ timed out: intranet did not respond", os.browser.error)
    }

    @Test
    fun `a timeout names the cause the network diagnoses, verbatim`() {
        boot()
        network.silent += "print"
        network.diagnoses[HostAddress("10.0.0.6")] = "no ARP reply from 10.0.0.6"

        os.printSpooler.perform("print", mapOf("document" to "report.pdf", "printer" to "printer1"))
        scheduler.advanceBy(5.seconds)

        val job = os.printSpooler.queue.single()
        assertEquals(PrintSpooler.Status.FAILED, job.status)
        assertEquals("printing report.pdf on printer1 timed out: no ARP reply from 10.0.0.6", job.detail)
    }

    @Test
    fun `a failure that is not a timeout keeps its own wording`() {
        boot()

        os.printSpooler.perform("print", mapOf("document" to "report.pdf", "printer" to "nobody"))

        assertEquals("can't find printer nobody: no such host 'nobody'", os.printSpooler.queue.single().detail)
    }

    @Test
    fun `a result arriving after the host restarted is discarded`() {
        boot()
        network.silent += "hold"
        os.browser.perform("open", mapOf("url" to "intranet"))

        os.shutdown()
        boot()
        network.held!!.invoke()

        assertEquals(WebBrowser.Status.IDLE, os.browser.status)
    }

    @Test
    fun `the print spooler finds the printer by name and records the job`() {
        boot()

        val outcome = os.printSpooler.perform("print", mapOf("document" to "report.pdf", "printer" to "printer1", "pages" to 3))

        assertEquals("job-1", outcome.data["jobId"])
        assertEquals(listOf(HostAddress("10.0.0.6") to "job-1:report.pdf"), network.printed)
        assertEquals(PrintSpooler.Status.ACCEPTED, os.printSpooler.queue.single().status)
        assertEquals("accepted by printer1", os.printSpooler.queue.single().detail)
    }

    @Test
    fun `the terminal runs a command over ssh and keeps a transcript`() {
        boot()

        os.terminal.perform("ssh", mapOf("target" to "student@lab1", "password" to "hunter2", "command" to "pwd"))
        os.terminal.perform("ssh", mapOf("target" to "student@lab1", "password" to "wrong", "command" to "pwd"))

        assertEquals(
            listOf(
                "$ ssh student@lab1 pwd", "/home/student",
                "$ ssh student@lab1 pwd", "ssh: lab1: Permission denied (invalid credentials)",
            ),
            os.terminal.transcript,
        )
        assertFalse(os.terminal.busy)
    }

    @Test
    fun `bad input is rejected without touching the network`() {
        boot()

        assertFalse(os.terminal.perform("ssh", mapOf("target" to "lab1", "password" to "x", "command" to "pwd")).accepted)
        assertFalse(os.browser.perform("open", mapOf("url" to "not a url")).accepted)
        assertFalse(os.printSpooler.perform("print", mapOf("document" to "a", "printer" to "printer1", "pages" to 0)).accepted)
        assertFalse(os.browser.perform("fly", emptyMap()).accepted)
        assertTrue(network.printed.isEmpty())
    }
}
