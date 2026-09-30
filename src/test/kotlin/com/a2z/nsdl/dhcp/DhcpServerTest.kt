package com.a2z.nsdl.dhcp

import com.a2z.nsdl.ip.Ipv4Stack
import com.a2z.nsdl.link.Cable
import com.a2z.nsdl.link.EthernetInterface
import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.ConfigSource
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.sim.WorkScope
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class DhcpServerTest {
    private val scheduler = VirtualScheduler()
    private val sink = RecordingSink()
    private val clientNic = EthernetInterface(ObjectId("client.eth0"), MacAddress.local(1), sink)
    private val serverNic = EthernetInterface(ObjectId("server.eth0"), MacAddress.local(2), sink)
    private val clientStack = Ipv4Stack(clientNic, sink)
    private val serverStack = Ipv4Stack(serverNic, sink)
    private val serverIp = Ipv4Address.parse("10.0.0.1")
    private val pool = DhcpPool(
        start = Ipv4Address.parse("10.0.0.100"), end = Ipv4Address.parse("10.0.0.101"),
        subnetMask = Ipv4Address.parse("255.255.255.0"), router = serverIp, leaseSeconds = 600,
    )
    private val server = DhcpServer(ObjectId("server.dhcp-server"), serverStack, pool, sink)
    private val replies = mutableListOf<DhcpMessage>()

    init {
        serverStack.applyConfig(Ipv4Config(serverIp, pool.subnetMask, source = ConfigSource.STATIC))
        clientStack.bind(DhcpMessage.CLIENT_PORT) { replies += it.datagram.payload as DhcpMessage }
        clientNic.enable(); serverNic.enable()
        Cable(ObjectId("c"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, sink).connect(clientNic, serverNic)
        server.start(WorkScope(scheduler))
    }

    private fun send(type: DhcpMessageType, xid: Int = 7, mac: MacAddress = clientNic.mac, requested: Ipv4Address? = null, serverId: Ipv4Address? = null) {
        val msg = DhcpMessage(BootOp.REQUEST, type, xid, mac, requestedIp = requested, serverId = serverId)
        clientStack.sendUdp(DhcpMessage.CLIENT_PORT, Ipv4Address.BROADCAST, DhcpMessage.SERVER_PORT, msg)
        scheduler.advanceBy(5.milliseconds)
    }

    @Test
    fun `DISCOVER is answered with a broadcast OFFER from the pool carrying the transaction and client identity`() {
        send(DhcpMessageType.DISCOVER, xid = 1234)

        val offer = replies.single()
        assertEquals(DhcpMessageType.OFFER, offer.type)
        assertEquals(BootOp.REPLY, offer.op)
        assertEquals(1234, offer.xid)
        assertEquals(clientNic.mac, offer.chaddr)
        assertEquals(Ipv4Address.parse("10.0.0.100"), offer.yiaddr)
        assertEquals(serverIp, offer.serverId)
        assertEquals(600L, offer.leaseSeconds)
    }

    @Test
    fun `REQUEST for the offered address is ACKed and recorded as a lease`() {
        send(DhcpMessageType.DISCOVER)
        send(DhcpMessageType.REQUEST, requested = replies.last().yiaddr, serverId = serverIp)

        assertEquals(DhcpMessageType.ACK, replies.last().type)
        @Suppress("UNCHECKED_CAST")
        val leases = server.snapshot().state["leases"] as List<Map<String, Any?>>
        assertEquals(listOf(mapOf("mac" to clientNic.mac.toString(), "address" to "10.0.0.100", "state" to "BOUND")), leases)
    }

    @Test
    fun `REQUEST for an address that was not offered is NAKed`() {
        send(DhcpMessageType.DISCOVER)
        send(DhcpMessageType.REQUEST, requested = Ipv4Address.parse("10.0.0.200"), serverId = serverIp)

        assertEquals(DhcpMessageType.NAK, replies.last().type)
    }

    @Test
    fun `REQUEST selecting another server withdraws our offer silently`() {
        send(DhcpMessageType.DISCOVER)
        send(DhcpMessageType.REQUEST, requested = replies.last().yiaddr, serverId = Ipv4Address.parse("10.0.0.9"))

        assertEquals(1, replies.size)
        assertEquals(emptyList<Any>(), server.snapshot().state["leases"])
    }

    @Test
    fun `a returning client is offered the same address and an exhausted pool stays silent`() {
        send(DhcpMessageType.DISCOVER, mac = MacAddress.local(10))
        send(DhcpMessageType.DISCOVER, mac = MacAddress.local(11))
        send(DhcpMessageType.DISCOVER, mac = MacAddress.local(10))
        send(DhcpMessageType.DISCOVER, mac = MacAddress.local(12))

        assertEquals(listOf("10.0.0.100", "10.0.0.101", "10.0.0.100"), replies.map { it.yiaddr.toString() })
    }

    @Test
    fun `a stopped server does not answer`() {
        server.stop()
        send(DhcpMessageType.DISCOVER)
        assertTrue(replies.isEmpty())
    }
}
