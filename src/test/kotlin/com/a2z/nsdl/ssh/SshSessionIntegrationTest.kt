package com.a2z.nsdl.ssh

import com.a2z.nsdl.device.HostBuilder
import com.a2z.nsdl.link.Cable
import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.ConfigSource
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.testing.RecordingSink
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A deliberately small SSH-*shaped* teaching protocol: a version "handshake", password
 * authentication, exactly one exec'd command, and one chunked file transfer, all over UDP with
 * typed immutable messages -- no real cryptography, no interactive shell, no multiple channels.
 * The client is scripted end to end by one Actionable action; everything after that first call is
 * driven reactively by the server's replies, the same way DhcpClient reacts to OFFER/ACK.
 */
class SshSessionIntegrationTest {
    private val mask = Ipv4Address.parse("255.255.255.0")
    private val clientAddress = Ipv4Address.parse("192.168.1.10")
    private val serverAddress = Ipv4Address.parse("192.168.1.20")
    private val clientMac = MacAddress.local(1)
    private val serverMac = MacAddress.local(2)

    private class Fixture(val client: SshClient, val server: SshServer, val scheduler: VirtualScheduler)

    private fun fixture(): Fixture {
        val scheduler = VirtualScheduler()
        val events = RecordingSink()
        lateinit var client: SshClient
        val computer = HostBuilder(ObjectId("computer1"), "computer", scheduler, events).run {
            val eth0 = ethernet("eth0", clientMac).also { it.applyConfig(Ipv4Config(clientAddress, mask, source = ConfigSource.STATIC)) }
            client = SshClient(id.child("ssh-client"), eth0)
            service(client)
            build { 1.milliseconds }
        }
        lateinit var server: SshServer
        val host = HostBuilder(ObjectId("server1"), "linux-host", scheduler, events).run {
            val eth0 = ethernet("eth0", serverMac).also { it.applyConfig(Ipv4Config(serverAddress, mask, source = ConfigSource.STATIC)) }
            server = SshServer(id.child("ssh-server"), eth0, username = "student", password = "hunter2")
            service(server)
            build { 1.milliseconds }
        }
        Cable(ObjectId("cable1"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, events)
            .connect(computer.interfaces.single(), host.interfaces.single())
        computer.powerOn()
        host.powerOn()
        scheduler.advanceBy(5.milliseconds)
        return Fixture(client, server, scheduler)
    }

    private fun Fixture.openSession(command: String = "neofetch", password: String = "hunter2") = client.perform(
        "openSession",
        mapOf(
            "username" to "student", "password" to password, "command" to command,
            "fileName" to "notes.txt", "fileBytes" to 2_500,
            "serverAddress" to serverAddress, "serverMac" to serverMac,
        ),
    )

    @Test
    fun `a client opens a session, runs one command, transfers a file, then disconnects`() {
        val fx = fixture()

        val outcome = fx.openSession()
        assertTrue(outcome.accepted)
        fx.scheduler.advanceBy(50.milliseconds)

        val session = fx.client.session ?: error("expected a session")
        assertEquals(SshSessionStatus.CLOSED, session.status)
        assertEquals("neofetch", session.lastOutput?.command)
        assertEquals(0, session.lastOutput?.exitCode)
        assertTrue(session.lastOutput!!.text.isNotBlank())

        assertEquals(1, fx.server.completedFiles.size)
        val file = fx.server.completedFiles.single()
        assertEquals("notes.txt", file.name)
        assertEquals(2_500, file.bytes)
        assertEquals(clientAddress, file.source)
    }

    @Test
    fun `wrong credentials fail authentication and no command runs`() {
        val fx = fixture()

        fx.openSession(password = "wrong")
        fx.scheduler.advanceBy(50.milliseconds)

        val session = fx.client.session ?: error("expected a session")
        assertEquals(SshSessionStatus.FAILED, session.status)
        assertNull(session.lastOutput)
        assertTrue(fx.server.completedFiles.isEmpty())
    }

    @Test
    fun `an unknown command still runs the session but reports a nonzero exit code`() {
        val fx = fixture()

        fx.openSession(command = "sudo rm -rf /")
        fx.scheduler.advanceBy(50.milliseconds)

        val session = fx.client.session ?: error("expected a session")
        assertEquals(SshSessionStatus.CLOSED, session.status)
        assertEquals(127, session.lastOutput?.exitCode)
    }

    @Test
    fun `opening a second session while one is already open is rejected`() {
        val fx = fixture()
        fx.openSession()

        val outcome = fx.openSession()

        assertTrue(!outcome.accepted)
    }
}
