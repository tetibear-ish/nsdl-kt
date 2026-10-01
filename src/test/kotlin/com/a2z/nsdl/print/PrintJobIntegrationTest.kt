package com.a2z.nsdl.print

import com.a2z.nsdl.device.EthernetSwitch
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
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrintJobIntegrationTest {
    @Test
    fun `computer sends a chunked print job through a switch and printer acknowledges it`() {
        val scheduler = VirtualScheduler()
        val events = RecordingSink()
        val mask = Ipv4Address.parse("255.255.255.0")
        val computerAddress = Ipv4Address.parse("192.168.1.10")
        val printerAddress = Ipv4Address.parse("192.168.1.20")
        val computerMac = MacAddress.local(1)
        val printerMac = MacAddress.local(2)

        lateinit var client: PrintClient
        val computer = HostBuilder(ObjectId("computer1"), "computer", scheduler, events).run {
            val eth0 = ethernet("eth0", computerMac).also {
                it.applyConfig(Ipv4Config(computerAddress, mask, source = ConfigSource.STATIC))
            }
            client = PrintClient(id.child("print-client"), eth0)
            service(client)
            build { 1.milliseconds }
        }
        lateinit var server: PrintServer
        val printer = HostBuilder(ObjectId("printer1"), "printer", scheduler, events).run {
            val eth0 = ethernet("eth0", printerMac).also {
                it.applyConfig(Ipv4Config(printerAddress, mask, source = ConfigSource.STATIC))
            }
            server = PrintServer(id.child("print-server"), eth0)
            service(server)
            build { 1.milliseconds }
        }
        val networkSwitch = EthernetSwitch(ObjectId("switch1"), 2, scheduler, events)
        Cable(ObjectId("computer-cable"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, events)
            .connect(computer.interfaces.single(), networkSwitch.ports[0])
        Cable(ObjectId("printer-cable"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, events)
            .connect(printer.interfaces.single(), networkSwitch.ports[1])

        networkSwitch.powerOn()
        printer.powerOn()
        computer.powerOn()
        scheduler.advanceBy(10.milliseconds)

        assertTrue(client.submit("job-1", "networking-notes.txt", 2_500, printerAddress, printerMac, chunkSize = 1_000))
        scheduler.advanceBy(20.milliseconds)

        assertEquals(
            CompletedPrintJob("job-1", "networking-notes.txt", 2_500, 3, computerAddress),
            server.completedJobs.single(),
        )
        assertEquals(PrintJobStatus.ACKNOWLEDGED, client.jobs.single().status)
        assertEquals(2_500, client.jobs.single().bytes)
    }

    @Test
    fun `printer rejects an incomplete print job instead of completing it`() {
        val transport = RecordingUdpTransport()
        val server = PrintServer(ObjectId("printer1.print-server"), transport)
        server.start(com.a2z.nsdl.sim.WorkScope(VirtualScheduler()))

        transport.deliver(PrintMessage.Start("job-1", "partial.txt", 10, 2))
        transport.deliver(PrintMessage.Chunk("job-1", 0, 6))
        transport.deliver(PrintMessage.Complete("job-1"))

        assertTrue(server.completedJobs.isEmpty())
        assertEquals(PrintReplyStatus.REJECTED, (transport.sent.last().payload as PrintMessage.Reply).status)
    }
}
