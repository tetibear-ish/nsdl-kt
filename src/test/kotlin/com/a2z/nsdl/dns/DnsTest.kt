package com.a2z.nsdl.dns

import com.a2z.nsdl.ip.Ipv4Stack
import com.a2z.nsdl.link.Cable
import com.a2z.nsdl.link.EthernetInterface
import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.model.EventPayload.FrameSent
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.ConfigSource
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import com.a2z.nsdl.net.Ipv4Packet
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.net.UdpDatagram
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.sim.WorkScope
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DnsTest {
    private val scheduler = VirtualScheduler()
    private val sink = RecordingSink()
    private val mask = Ipv4Address.parse("255.255.255.0")
    private val serverAddress = Ipv4Address.parse("192.168.1.1")
    private val clientNic = EthernetInterface(ObjectId("pc.eth0"), MacAddress.local(1), sink)
    private val serverNic = EthernetInterface(ObjectId("gw.eth0"), MacAddress.local(2), sink)
    private val clientStack = Ipv4Stack(clientNic, sink)
    private val serverStack = Ipv4Stack(serverNic, sink)
    private val server = DnsServer(ObjectId("gw.dns-server"), serverStack, sink)
    private val resolver = DnsResolver(ObjectId("pc.dns-resolver"), clientStack, sink)
    private val results = mutableListOf<Resolution>()

    init {
        clientNic.enable(); serverNic.enable()
        Cable(ObjectId("c"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, sink).connect(clientNic, serverNic)
        serverStack.applyConfig(Ipv4Config(serverAddress, mask, source = ConfigSource.STATIC))
        clientStack.applyConfig(Ipv4Config(Ipv4Address.parse("192.168.1.100"), mask, source = ConfigSource.DHCP, dnsServer = serverAddress))
        server.addStatic("gw", serverAddress)
        server.start(WorkScope(scheduler))
        resolver.start(WorkScope(scheduler))
    }

    private fun queriesSent() = sink.of<FrameSent>(clientNic.id).count { ((it.frame.payload as? Ipv4Packet)?.payload as? UdpDatagram)?.payload is DnsMessage.Query }

    @Test
    fun `a registered name resolves through a query to the configured server`() {
        server.register("Printer1", Ipv4Address.parse("192.168.1.101"))

        resolver.resolve("printer1.") { results += it }
        scheduler.advanceBy(10.milliseconds)

        assertEquals(listOf(Resolution("printer1", Ipv4Address.parse("192.168.1.101"))), results)
    }

    @Test
    fun `an unknown name fails with NXDOMAIN and is not cached`() {
        resolver.resolve("nobody") { results += it }
        scheduler.advanceBy(10.milliseconds)

        assertNull(results.single().address)
        assertEquals("no such host 'nobody'", results.single().error)
    }

    @Test
    fun `answers are cached and concurrent lookups share one query`() {
        resolver.resolve("gw") { results += it }
        resolver.resolve("GW") { results += it }
        scheduler.advanceBy(10.milliseconds)
        resolver.resolve("gw") { results += it }

        assertEquals(3, results.size)
        assertEquals(setOf(serverAddress), results.map { it.address }.toSet())
        assertEquals(1, queriesSent())
    }

    @Test
    fun `a dotted quad needs no query`() {
        resolver.resolve("10.1.2.3") { results += it }

        assertEquals(Ipv4Address.parse("10.1.2.3"), results.single().address)
        assertEquals(0, queriesSent())
    }

    @Test
    fun `an unanswered query is retried, then fails`() {
        server.stop()

        resolver.resolve("gw") { results += it }
        scheduler.advanceBy(10.seconds)

        assertEquals(3, queriesSent())
        assertEquals("DNS server $serverAddress did not answer", results.single().error)
    }

    @Test
    fun `dynamic records follow leases, never override static ones, and vanish when the server stops`() {
        server.register("gw", Ipv4Address.parse("192.168.1.200"))
        server.register("laptop", Ipv4Address.parse("192.168.1.150"))
        assertEquals(serverAddress, server.lookup("gw"))
        assertEquals(Ipv4Address.parse("192.168.1.150"), server.lookup("laptop"))

        server.unregister("laptop", Ipv4Address.parse("192.168.1.150"))
        assertNull(server.lookup("laptop"))

        server.register("laptop", Ipv4Address.parse("192.168.1.150"))
        server.stop()
        assertNull(server.lookup("laptop"))
        assertEquals(serverAddress, server.lookup("gw"), "static records are configuration")
    }
}
