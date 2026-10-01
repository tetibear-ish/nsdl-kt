package com.a2z.nsdl.ip

import com.a2z.nsdl.device.EthernetSwitch
import com.a2z.nsdl.link.Cable
import com.a2z.nsdl.link.EthernetInterface
import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.model.DecisionAction
import com.a2z.nsdl.model.EventPayload.DecisionRecorded
import com.a2z.nsdl.model.EventPayload.FrameDropped
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.Responsibility
import com.a2z.nsdl.net.ConfigSource
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.net.OpaquePayload
import com.a2z.nsdl.net.UdpDatagram
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class RouterTest {
    private val scheduler = VirtualScheduler()
    private val events = RecordingSink()

    private val lanIf = EthernetInterface(ObjectId("gw.lan"), MacAddress.local(10), events)
    private val wanIf = EthernetInterface(ObjectId("gw.wan"), MacAddress.local(11), events)
    private val router = Router(ObjectId("gw.router"), listOf(lanIf, wanIf), events)

    private val lanSwitch = EthernetSwitch(ObjectId("lan-switch"), portCount = 3, scheduler = scheduler, events = events)
    private val hostA = EthernetInterface(ObjectId("a.eth0"), MacAddress.local(20), events)
    private val hostA2 = EthernetInterface(ObjectId("a2.eth0"), MacAddress.local(21), events)
    private val hostB = EthernetInterface(ObjectId("b.eth0"), MacAddress.local(22), events)
    private val stackA = Ipv4Stack(hostA, events)
    private val stackA2 = Ipv4Stack(hostA2, events)
    private val stackB = Ipv4Stack(hostB, events)

    @BeforeEach
    fun wire() {
        router.configure(lanIf.id, Ipv4Config(Ipv4Address.parse("10.0.1.1"), Ipv4Address.parse("255.255.255.0"), source = ConfigSource.STATIC))
        router.configure(wanIf.id, Ipv4Config(Ipv4Address.parse("10.0.2.1"), Ipv4Address.parse("255.255.255.0"), source = ConfigSource.STATIC))
        stackA.applyConfig(Ipv4Config(Ipv4Address.parse("10.0.1.10"), Ipv4Address.parse("255.255.255.0"), source = ConfigSource.STATIC))
        stackA2.applyConfig(Ipv4Config(Ipv4Address.parse("10.0.1.20"), Ipv4Address.parse("255.255.255.0"), source = ConfigSource.STATIC))
        stackB.applyConfig(Ipv4Config(Ipv4Address.parse("10.0.2.10"), Ipv4Address.parse("255.255.255.0"), source = ConfigSource.STATIC))

        lanSwitch.powerOn()
        lanIf.enable(); wanIf.enable(); hostA.enable(); hostA2.enable(); hostB.enable()
        scheduler.advanceBy(0.milliseconds)

        Cable(ObjectId("c-lan-gw"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, events).connect(lanIf, lanSwitch.ports[0])
        Cable(ObjectId("c-lan-a"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, events).connect(hostA, lanSwitch.ports[1])
        Cable(ObjectId("c-lan-a2"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, events).connect(hostA2, lanSwitch.ports[2])
        Cable(ObjectId("c-wan-b"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, events).connect(wanIf, hostB)
    }

    private fun send(from: Ipv4Stack, dst: Ipv4Address, gatewayMac: MacAddress, ttl: Int = 64) {
        from.sendUdp(9000, dst, 9001, OpaquePayload("payload"), dstMac = gatewayMac, ttl = ttl)
    }

    private fun bindEcho(stack: Ipv4Stack): MutableList<OpaquePayload> {
        val received = mutableListOf<OpaquePayload>()
        stack.bind(9001) { d -> received += d.datagram.payload as OpaquePayload }
        return received
    }

    @Test
    fun `a directly connected destination on the same interface is delivered back out that interface`() {
        val receivedAtA2 = bindEcho(stackA2)

        send(stackA, Ipv4Address.parse("10.0.1.20"), lanIf.mac)
        scheduler.advanceBy(10.milliseconds)

        assertEquals(listOf(OpaquePayload("payload")), receivedAtA2)
        val record = events.of<DecisionRecorded>(router.id).map { it.record }.single()
        assertEquals(DecisionAction.FORWARD, record.decision)
        assertEquals(Responsibility.ROUTING, record.responsibility)
        assertEquals("forwarded via connected route", record.reason)
        assertEquals("gw.lan", record.attributes["egressInterface"])
    }

    @Test
    fun `a destination on a different attached subnet is routed across interfaces`() {
        val receivedAtB = bindEcho(stackB)

        send(stackA, Ipv4Address.parse("10.0.2.10"), lanIf.mac)
        scheduler.advanceBy(10.milliseconds)

        assertEquals(listOf(OpaquePayload("payload")), receivedAtB)
        val record = events.of<DecisionRecorded>(router.id).map { it.record }.single()
        assertEquals("gw.wan", record.attributes["egressInterface"])
        assertEquals("63", record.attributes["ttlAfter"])
    }

    @Test
    fun `a static route sends traffic for an unattached network out the configured interface`() {
        router.addRoute(Route(Ipv4Address.parse("203.0.113.0"), 24, nextHop = Ipv4Address.parse("10.0.2.254"), interfaceId = wanIf.id, kind = RouteKind.STATIC))

        send(stackA, Ipv4Address.parse("203.0.113.5"), lanIf.mac)
        scheduler.advanceBy(10.milliseconds)

        val record = events.of<DecisionRecorded>(router.id).map { it.record }.single()
        assertEquals(DecisionAction.FORWARD, record.decision)
        assertEquals("forwarded via static route", record.reason)
        assertEquals("10.0.2.254", record.attributes["nextHop"])
        assertEquals("gw.wan", record.attributes["egressInterface"])
    }

    @Test
    fun `a default route sends unmatched traffic out its configured interface`() {
        router.addRoute(Route.default(nextHop = Ipv4Address.parse("10.0.2.254"), interfaceId = wanIf.id))

        send(stackA, Ipv4Address.parse("8.8.8.8"), lanIf.mac)
        scheduler.advanceBy(10.milliseconds)

        val record = events.of<DecisionRecorded>(router.id).map { it.record }.single()
        assertEquals("forwarded via default route", record.reason)
        assertEquals("gw.wan", record.attributes["egressInterface"])
    }

    @Test
    fun `a destination with no matching route is dropped`() {
        send(stackA, Ipv4Address.parse("172.16.0.5"), lanIf.mac)
        scheduler.advanceBy(10.milliseconds)

        val record = events.of<DecisionRecorded>(router.id).map { it.record }.single()
        assertEquals(DecisionAction.DROP, record.decision)
        assertEquals("no matching route", record.reason)
    }

    @Test
    fun `a packet arriving with an expired ttl is dropped before forwarding`() {
        val receivedAtB = bindEcho(stackB)

        send(stackA, Ipv4Address.parse("10.0.2.10"), lanIf.mac, ttl = 1)
        scheduler.advanceBy(10.milliseconds)

        assertTrue(receivedAtB.isEmpty())
        val record = events.of<DecisionRecorded>(router.id).map { it.record }.single()
        assertEquals(DecisionAction.DROP, record.decision)
        assertEquals("ttl expired", record.reason)
    }

    @Test
    fun `forwarding out a disabled interface is dropped and reported by both the interface and the router`() {
        wanIf.disable()
        val receivedAtB = bindEcho(stackB)

        send(stackA, Ipv4Address.parse("10.0.2.10"), lanIf.mac)
        scheduler.advanceBy(10.milliseconds)

        assertTrue(receivedAtB.isEmpty())
        assertEquals(1, events.of<FrameDropped>(wanIf.id).size)
        val record = events.of<DecisionRecorded>(router.id).map { it.record }.single()
        assertEquals(DecisionAction.DROP, record.decision)
        assertEquals("egress interface unavailable", record.reason)
    }

    @Test
    fun `DHCP-style service bound only on the lan transport is unreachable from the wan interface`() {
        val lanRequests = mutableListOf<OpaquePayload>()
        router.transport(lanIf.id).bind(67) { d -> lanRequests += d.datagram.payload as OpaquePayload }

        stackA.sendUdp(68, Ipv4Address.BROADCAST, 67, OpaquePayload("discover-from-lan"))
        stackB.sendUdp(68, Ipv4Address.BROADCAST, 67, OpaquePayload("discover-from-wan"))
        scheduler.advanceBy(10.milliseconds)

        assertEquals(listOf(OpaquePayload("discover-from-lan")), lanRequests)
    }
}
