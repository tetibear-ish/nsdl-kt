package com.a2z.nsdl.ip

import com.a2z.nsdl.link.Cable
import com.a2z.nsdl.link.EthernetInterface
import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.model.DecisionAction
import com.a2z.nsdl.model.EventPayload.DecisionRecorded
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.Responsibility
import com.a2z.nsdl.net.ConfigSource
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.net.OpaquePayload
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * A home network behind a NAT router: inside host 192.168.1.10 on lan, an internet server 203.0.113.20
 * on wan, router wan address 203.0.113.1.
 */
class NatTest {
    private val scheduler = VirtualScheduler()
    private val events = RecordingSink()
    private val mask = Ipv4Address.parse("255.255.255.0")
    private val insideAddress = Ipv4Address.parse("192.168.1.10")
    private val publicAddress = Ipv4Address.parse("203.0.113.1")
    private val serverAddress = Ipv4Address.parse("203.0.113.20")

    private val lanIf = EthernetInterface(ObjectId("gw.lan"), MacAddress.local(10), events)
    private val wanIf = EthernetInterface(ObjectId("gw.wan"), MacAddress.local(11), events)
    private val hostIf = EthernetInterface(ObjectId("pc.eth0"), MacAddress.local(20), events)
    private val serverIf = EthernetInterface(ObjectId("srv.eth0"), MacAddress.local(30), events)
    private val host = Ipv4Stack(hostIf, events)
    private val server = Ipv4Stack(serverIf, events)
    private val atHost = mutableListOf<ReceivedDatagram>()
    private val atServer = mutableListOf<ReceivedDatagram>()

    private fun router(vararg forwards: PortForward) =
        Router(ObjectId("gw.router"), listOf(lanIf, wanIf), events, nat = NatConfig(lanIf.id, wanIf.id, forwards.toList())).also {
            it.configure(lanIf.id, Ipv4Config(Ipv4Address.parse("192.168.1.1"), mask, source = ConfigSource.STATIC))
            it.configure(wanIf.id, Ipv4Config(publicAddress, mask, source = ConfigSource.STATIC))
            host.applyConfig(Ipv4Config(insideAddress, mask, router = Ipv4Address.parse("192.168.1.1"), source = ConfigSource.STATIC))
            server.applyConfig(Ipv4Config(serverAddress, mask, source = ConfigSource.STATIC))
            lanIf.enable(); wanIf.enable(); hostIf.enable(); serverIf.enable()
            Cable(ObjectId("c-lan"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, events).connect(hostIf, lanIf)
            Cable(ObjectId("c-wan"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, events).connect(wanIf, serverIf)
            host.bind(5000) { atHost += it }
            server.bind(80) { atServer += it }
        }

    private fun settle() = scheduler.advanceBy(20.milliseconds)

    private fun natDecisions() = events.of<DecisionRecorded>().map { it.record }.filter { it.responsibility == Responsibility.NAT }

    @Test
    fun `outbound traffic leaves with the router's public address and a mapped port`() {
        router()

        host.sendUdp(5000, serverAddress, 80, OpaquePayload("GET /"))
        settle()

        val seen = atServer.single()
        assertEquals(publicAddress, seen.packet.src, "the server never sees the private address")
        assertEquals(50_000, seen.datagram.srcPort)
        assertEquals("192.168.1.10:5000", natDecisions().single().attributes["original"])
        assertEquals("203.0.113.1:50000", natDecisions().single().attributes["translated"])
    }

    @Test
    fun `the reply is translated back to the inside host`() {
        router()
        host.sendUdp(5000, serverAddress, 80, OpaquePayload("GET /"))
        settle()

        server.sendUdp(80, publicAddress, 50_000, OpaquePayload("200 OK"))
        settle()

        val reply = atHost.single()
        assertEquals(serverAddress, reply.packet.src)
        assertEquals(insideAddress, reply.packet.dst)
        assertEquals(5000, reply.datagram.dstPort)
    }

    @Test
    fun `the same inside port reuses its mapping`() {
        router()
        host.sendUdp(5000, serverAddress, 80, OpaquePayload("one"))
        host.sendUdp(5000, serverAddress, 80, OpaquePayload("two"))
        settle()

        assertEquals(listOf(50_000, 50_000), atServer.map { it.datagram.srcPort })
        assertEquals(listOf("source translated (new mapping)", "source translated"), natDecisions().map { it.reason })
    }

    @Test
    fun `unsolicited inbound traffic is dropped, even from a peer the mapping was not made for`() {
        router()
        host.sendUdp(5000, serverAddress, 80, OpaquePayload("GET /"))
        settle()

        server.sendUdp(81, publicAddress, 50_000, OpaquePayload("from another port"))
        server.sendUdp(80, publicAddress, 50_001, OpaquePayload("to an unmapped port"))
        settle()

        assertTrue(atHost.isEmpty())
        assertEquals(2, natDecisions().count { it.decision == DecisionAction.DROP })
    }

    @Test
    fun `a port forward lets an outside host start the conversation`() {
        router(PortForward(8080, insideAddress, 5000))

        server.sendUdp(80, publicAddress, 8080, OpaquePayload("hello inside"))
        settle()

        assertEquals(insideAddress, atHost.single().packet.dst)
        host.sendUdp(5000, serverAddress, 80, OpaquePayload("hello outside"))
        settle()
        assertEquals(8080, atServer.single().datagram.srcPort, "the reply leaves on the forwarded port")
    }

    @Test
    fun `dynamic mappings are forgotten when the link goes down`() {
        router()
        host.sendUdp(5000, serverAddress, 80, OpaquePayload("GET /"))
        settle()

        wanIf.disable()
        wanIf.enable()
        server.sendUdp(80, publicAddress, 50_000, OpaquePayload("late reply"))
        settle()

        assertTrue(atHost.isEmpty())
    }

    @Test
    fun `port forward rules parse, and malformed ones are rejected`() {
        assertEquals(
            listOf(PortForward(8080, insideAddress, 80), PortForward(2222, Ipv4Address.parse("192.168.1.20"), 22)),
            PortForward.parseList(" 8080>192.168.1.10:80 , 2222>192.168.1.20:22 "),
        )
        assertEquals(emptyList<PortForward>(), PortForward.parseList(""))
        assertNull(PortForward.parseList("8080>192.168.1.10"))
        assertNull(PortForward.parseList("80>192.168.1.10:80, 80>192.168.1.11:80"), "duplicate outside port")
        assertNull(PortForward.parseList("70000>192.168.1.10:80"))
    }
}
