package com.a2z.nsdl.device

import com.a2z.nsdl.dhcp.DhcpClient
import com.a2z.nsdl.dhcp.DhcpMessage
import com.a2z.nsdl.dhcp.DhcpMessageType
import com.a2z.nsdl.dhcp.DhcpPool
import com.a2z.nsdl.dhcp.DhcpServer
import com.a2z.nsdl.link.Cable
import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.model.EventPayload.PacketAccepted
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.ConfigSource
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.net.UdpDatagram
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.testing.RecordingSink
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SwitchDhcpIntegrationTest {
    @Test
    fun `printer discovers gateway through a switch and receives an address inside its DHCP pool`() {
        val scheduler = VirtualScheduler()
        val events = RecordingSink()
        val poolStart = Ipv4Address.parse("10.20.30.100")
        val poolEnd = Ipv4Address.parse("10.20.30.110")
        val mask = Ipv4Address.parse("255.255.255.0")
        val gatewayAddress = Ipv4Address.parse("10.20.30.1")

        lateinit var printerStack: com.a2z.nsdl.ip.Ipv4Stack
        val printer = HostBuilder(ObjectId("printer"), "printer", scheduler, events).run {
            printerStack = ethernet("eth0", MacAddress.local(1))
            service(DhcpClient(id.child("dhcp-client"), printerStack, printerStack, Random(7), events))
            build { 1.seconds }
        }
        val gateway = HostBuilder(ObjectId("gateway"), "gateway", scheduler, events).run {
            val eth0 = ethernet("eth0", MacAddress.local(2))
            eth0.applyConfig(Ipv4Config(gatewayAddress, mask, source = ConfigSource.STATIC))
            service(DhcpServer(
                id.child("dhcp-server"),
                eth0,
                DhcpPool(poolStart, poolEnd, mask, gatewayAddress, leaseSeconds = 3600),
                events,
            ))
            build { 1.seconds }
        }
        val networkSwitch = EthernetSwitch(ObjectId("switch"), portCount = 2, scheduler = scheduler, events = events)
        val printerCable = Cable(ObjectId("printer-cable"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, events)
        val gatewayCable = Cable(ObjectId("gateway-cable"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, events)
        printerCable.connect(printer.interfaces.single(), networkSwitch.ports[0])
        gatewayCable.connect(gateway.interfaces.single(), networkSwitch.ports[1])

        networkSwitch.powerOn()
        gateway.powerOn()
        printer.powerOn()
        scheduler.advanceBy(5.seconds)

        val discoverReachedGateway = events.of<PacketAccepted>(gateway.interfaces.single().id).any {
            ((it.packet.payload as? UdpDatagram)?.payload as? DhcpMessage)?.type == DhcpMessageType.DISCOVER
        }
        assertTrue(discoverReachedGateway, "the switch must forward the printer's DHCP broadcast")

        val leasedAddress = printerStack.config?.address
        assertNotNull(leasedAddress, "the printer should receive a DHCP lease")
        assertTrue(leasedAddress!!.isBetween(poolStart, poolEnd), "$leasedAddress is outside $poolStart..$poolEnd")
        assertEquals(ConfigSource.DHCP, printerStack.config?.source)
    }

    private fun Ipv4Address.isBetween(start: Ipv4Address, end: Ipv4Address): Boolean =
        Integer.compareUnsigned(bits, start.bits) >= 0 && Integer.compareUnsigned(bits, end.bits) <= 0
}
