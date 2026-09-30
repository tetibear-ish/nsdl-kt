package com.a2z.nsdl.device

import com.a2z.nsdl.dhcp.DhcpClient
import com.a2z.nsdl.dhcp.DhcpClientState
import com.a2z.nsdl.dhcp.DhcpMessage
import com.a2z.nsdl.dhcp.DhcpPool
import com.a2z.nsdl.dhcp.DhcpServer
import com.a2z.nsdl.link.Cable
import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.model.EventPayload.FrameReceived
import com.a2z.nsdl.model.EventPayload.FrameSent
import com.a2z.nsdl.model.EventPayload.LinkStateChanged
import com.a2z.nsdl.model.EventPayload.PacketAccepted
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.PowerState
import com.a2z.nsdl.net.ConfigSource
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.net.UdpDatagram
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class DhcpOverCableIntegrationTest {
    private val scheduler = VirtualScheduler()
    private val sink = RecordingSink()
    private val random = Random(7)
    private val mask = Ipv4Address.parse("255.255.255.0")
    private val serverIp = Ipv4Address.parse("10.0.0.1")

    private lateinit var dhcpClient: DhcpClient
    private val printer = HostBuilder(ObjectId("printer"), "printer", scheduler, sink).run {
        val eth0 = ethernet("eth0", MacAddress.local(1))
        dhcpClient = DhcpClient(id.child("dhcp-client"), eth0, eth0, random, sink)
        service(dhcpClient)
        build(bootDuration = { 3.seconds })
    }
    private val server = HostBuilder(ObjectId("server"), "dhcp-server", scheduler, sink).run {
        val eth0 = ethernet("eth0", MacAddress.local(2))
        eth0.applyConfig(Ipv4Config(serverIp, mask, source = ConfigSource.STATIC))
        service(DhcpServer(id.child("dhcp-server"), eth0, DhcpPool(Ipv4Address.parse("10.0.0.100"), Ipv4Address.parse("10.0.0.110"), mask, serverIp, 3600), sink))
        build(bootDuration = { 1.seconds })
    }
    private val printerEth0 = printer.interfaces.single()
    private val serverEth0 = server.interfaces.single()
    private val cable = Cable(ObjectId("cable1"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, sink)

    private fun printerAddress(): Any? = (printerEth0.snapshot().state["ipv4"] as Map<*, *>?)?.get("address")

    private fun dhcpTypesAccepted() = sink.events
        .filter { it.second is PacketAccepted }
        .map { (src, p) -> src to ((p as PacketAccepted).packet.payload as UdpDatagram).payload }
        .filter { it.second is DhcpMessage }
        .map { (src, m) -> "${src}:${(m as DhcpMessage).type}" }

    @Test
    fun `printer acquires an address from the server through the cable after both boot`() {
        cable.connect(printerEth0, serverEth0)
        server.powerOn()
        printer.powerOn()

        scheduler.advanceBy(2.seconds)
        assertNull(printerAddress(), "still booting: no communication yet")
        assertTrue(sink.of<FrameSent>(printerEth0.id).isEmpty())

        scheduler.advanceBy(2.seconds)

        assertEquals("10.0.0.100", printerAddress())
        assertEquals(DhcpClientState.BOUND, dhcpClient.state)
        assertEquals(
            listOf("server.eth0:DISCOVER", "printer.eth0:OFFER", "server.eth0:REQUEST", "printer.eth0:ACK"),
            dhcpTypesAccepted(),
            "each message was accepted by the other endpoint's stack",
        )
        assertEquals(sink.of<FrameSent>(printerEth0.id).map { it.frame }, sink.of<FrameReceived>(serverEth0.id).map { it.frame })
    }

    @Test
    fun `without the cable no DHCP message reaches the server and no address is acquired`() {
        server.powerOn(); printer.powerOn()
        scheduler.advanceBy(60.seconds)

        assertNull(printerAddress())
        assertTrue(sink.of<FrameReceived>(serverEth0.id).isEmpty())
        assertEquals(DhcpClientState.INIT, dhcpClient.state)

        cable.connect(printerEth0, serverEth0)
        scheduler.advanceBy(1.seconds)
        assertEquals("10.0.0.100", printerAddress(), "connecting later starts acquisition immediately")
    }

    @Test
    fun `powering off the printer keeps the cable attached but takes the link down on both ends`() {
        cable.connect(printerEth0, serverEth0)
        server.powerOn(); printer.powerOn()
        scheduler.advanceBy(5.seconds)
        sink.clear()

        printer.powerOff()

        assertEquals(PowerState.OFF, printer.powerState)
        assertTrue(printerEth0.isAttached && cable.isConnected)
        assertFalse(printerEth0.linkUp || serverEth0.linkUp)
        assertEquals(listOf(LinkStateChanged(false)), sink.of<LinkStateChanged>(serverEth0.id))
        assertNull(printerAddress(), "volatile DHCP config is cleared")
        assertEquals("PoweredOff", sink.names(printer.id).last())
    }

    @Test
    fun `shutdown while waiting for DHCP stops retransmissions`() {
        // A peer that is powered (so the link genuinely comes up) but runs no DHCP service,
        // so DISCOVER goes unanswered and the client keeps retrying instead of settling.
        val idlePeer = HostBuilder(ObjectId("idle-peer"), "idle-peer", scheduler, sink).run {
            ethernet("eth0", MacAddress.local(3))
            build(bootDuration = { 0.seconds })
        }
        cable.connect(printerEth0, idlePeer.interfaces.single())
        idlePeer.powerOn()
        printer.powerOn()
        scheduler.advanceBy(10.seconds)
        assertEquals(DhcpClientState.SELECTING, dhcpClient.state)

        printer.powerOff()
        val sentBefore = sink.of<FrameSent>().size
        scheduler.advanceBy(60.seconds * 5)

        assertEquals(sentBefore, sink.of<FrameSent>().size)
        assertEquals(DhcpClientState.STOPPED, dhcpClient.state)
    }

    @Test
    fun `a powered off server does not respond to traffic`() {
        cable.connect(printerEth0, serverEth0)
        server.powerOn(); printer.powerOn()
        scheduler.advanceBy(1.seconds)
        server.powerOff()
        scheduler.advanceBy(30.seconds)

        assertNull(printerAddress())
        assertTrue(sink.of<FrameReceived>(serverEth0.id).isEmpty())
    }

    @Test
    fun `complete off - on - acquire - off - on cycle acquires again with a new transaction`() {
        cable.connect(printerEth0, serverEth0)
        server.powerOn()
        printer.powerOn()
        scheduler.advanceBy(5.seconds)
        assertEquals("10.0.0.100", printerAddress())
        val firstXid = dhcpClient.snapshot().state["xid"]

        printer.powerOff()
        assertNull(printerAddress())
        scheduler.advanceBy(5.seconds)

        printer.powerOn()
        scheduler.advanceBy(2.seconds)
        assertNull(printerAddress(), "not before boot completes")
        scheduler.advanceBy(3.seconds)

        assertEquals("10.0.0.100", printerAddress(), "server re-offers the same address to the same chaddr")
        assertNotEquals(firstXid, dhcpClient.snapshot().state["xid"])
        assertEquals(
            listOf("PowerOnStarted", "BootCompleted", "PoweredOff", "PowerOnStarted", "BootCompleted"),
            sink.names(printer.id),
        )
    }

    @Test
    fun `device snapshot shows power state, persistent config and component ids`() {
        val snap = printer.snapshot()
        assertEquals("OFF", snap.state["power"])
        assertEquals(3000L, snap.state["bootMs"])
        assertEquals(listOf(printerEth0.id), snap.relations["interfaces"])
        assertEquals(listOf(dhcpClient.id), snap.relations["services"])
    }
}
