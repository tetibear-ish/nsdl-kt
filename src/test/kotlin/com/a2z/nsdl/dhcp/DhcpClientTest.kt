package com.a2z.nsdl.dhcp

import com.a2z.nsdl.ip.Ipv4Stack
import com.a2z.nsdl.ip.ReceivedDatagram
import com.a2z.nsdl.link.Cable
import com.a2z.nsdl.link.EthernetInterface
import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.model.EventPayload.NetworkConfigChanged
import com.a2z.nsdl.model.EventPayload.ProtocolStateChanged
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.ConfigSource
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.sim.WorkScope
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DhcpClientTest {
    private val scheduler = VirtualScheduler()
    private val sink = RecordingSink()
    private val clientNic = EthernetInterface(ObjectId("printer.eth0"), MacAddress.local(1), sink)
    private val serverNic = EthernetInterface(ObjectId("server.eth0"), MacAddress.local(2), sink)
    private val clientStack = Ipv4Stack(clientNic, sink)
    private val serverStack = Ipv4Stack(serverNic, sink)
    private val cable = Cable(ObjectId("cable"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, sink)
    private val timers = DhcpClientTimers(jitter = 0.milliseconds)
    private val client = DhcpClient(ObjectId("printer.dhcp-client"), clientStack, clientStack, Random(42), sink, timers)

    private val serverIp = Ipv4Address.parse("10.0.0.1")
    private val offeredIp = Ipv4Address.parse("10.0.0.100")
    private val mask = Ipv4Address.parse("255.255.255.0")
    private val seenByServer = mutableListOf<ReceivedDatagram>()
    private val requests get() = seenByServer.map { it.datagram.payload as DhcpMessage }

    init {
        serverStack.applyConfig(Ipv4Config(serverIp, mask, source = ConfigSource.STATIC))
        serverStack.bind(DhcpMessage.SERVER_PORT) { seenByServer += it }
        clientNic.enable(); serverNic.enable()
    }

    private fun connectAndStart() {
        cable.connect(clientNic, serverNic)
        client.start(WorkScope(scheduler))
        settle()
    }

    private fun settle() = scheduler.advanceBy(5.milliseconds)

    private fun reply(
        type: DhcpMessageType,
        to: DhcpMessage = requests.last(),
        xid: Int = to.xid,
        chaddr: MacAddress = to.chaddr,
        serverId: Ipv4Address = serverIp,
    ) {
        val msg = DhcpMessage(
            op = BootOp.REPLY, type = type, xid = xid, chaddr = chaddr,
            yiaddr = if (type == DhcpMessageType.NAK) Ipv4Address.ANY else offeredIp,
            serverId = serverId, subnetMask = mask, router = serverIp, leaseSeconds = 3600,
        )
        serverStack.sendUdp(DhcpMessage.SERVER_PORT, Ipv4Address.BROADCAST, DhcpMessage.CLIENT_PORT, msg)
        settle()
    }

    @Test
    fun `starts with a broadcast DISCOVER from 0_0_0_0 port 68 to 255_255_255_255 port 67`() {
        connectAndStart()

        val d = seenByServer.single()
        val msg = d.datagram.payload as DhcpMessage
        assertEquals(DhcpMessageType.DISCOVER, msg.type)
        assertEquals(Ipv4Address.ANY, d.packet.src)
        assertEquals(Ipv4Address.BROADCAST, d.packet.dst)
        assertEquals(68, d.datagram.srcPort)
        assertEquals(clientNic.mac, msg.chaddr)
        assertTrue(msg.broadcast, "client asks for broadcast replies because it cannot receive unicast before configuration")
        assertEquals(DhcpClientState.SELECTING, client.state)
    }

    @Test
    fun `an OFFER alone leads to a REQUEST but configures nothing`() {
        connectAndStart()
        reply(DhcpMessageType.OFFER)

        val req = requests.last()
        assertEquals(DhcpMessageType.REQUEST, req.type)
        assertEquals(requests.first().xid, req.xid)
        assertEquals(serverIp, req.serverId)
        assertEquals(offeredIp, req.requestedIp)
        assertEquals(DhcpClientState.REQUESTING, client.state)
        assertNull(clientStack.config)
    }

    @Test
    fun `an acceptable ACK applies the configuration`() {
        connectAndStart()
        reply(DhcpMessageType.OFFER)
        reply(DhcpMessageType.ACK)

        assertEquals(DhcpClientState.BOUND, client.state)
        assertEquals(
            Ipv4Config(offeredIp, mask, serverIp, ConfigSource.DHCP, leaseSeconds = 3600, server = serverIp),
            clientStack.config,
        )
        assertEquals(
            listOf("STOPPED>INIT", "INIT>SELECTING", "SELECTING>REQUESTING", "REQUESTING>BOUND"),
            sink.of<ProtocolStateChanged>(client.id).map { "${it.from}>${it.to}" },
        )
    }

    @Test
    fun `replies for another transaction or another client are ignored`() {
        connectAndStart()
        reply(DhcpMessageType.OFFER, xid = requests.last().xid + 1)
        reply(DhcpMessageType.OFFER, chaddr = MacAddress.local(77))
        assertEquals(DhcpClientState.SELECTING, client.state)
        assertEquals(1, requests.size)

        reply(DhcpMessageType.OFFER)
        reply(DhcpMessageType.ACK, xid = requests.last().xid + 1)
        reply(DhcpMessageType.ACK, serverId = Ipv4Address.parse("10.0.0.2"))
        assertEquals(DhcpClientState.REQUESTING, client.state, "ACK from an unselected server is rejected")
        assertNull(clientStack.config)
    }

    @Test
    fun `DISCOVER is retransmitted with exponential backoff in virtual time`() {
        connectAndStart()
        scheduler.advanceBy(3.seconds)
        assertEquals(1, requests.size)
        scheduler.advanceBy(1.seconds)
        assertEquals(2, requests.size, "first retry after 4s")
        scheduler.advanceBy(8.seconds)
        assertEquals(3, requests.size, "second retry 8s later")
        assertTrue(requests.all { it.type == DhcpMessageType.DISCOVER && it.xid == requests.first().xid })
    }

    @Test
    fun `unanswered REQUESTs eventually restart discovery with a new transaction`() {
        connectAndStart()
        reply(DhcpMessageType.OFFER)
        val xid = requests.last().xid

        scheduler.advanceBy(120.seconds)

        val requestCount = requests.count { it.type == DhcpMessageType.REQUEST }
        assertEquals(timers.maxRequestAttempts, requestCount)
        val rediscover = requests.last { it.type == DhcpMessageType.DISCOVER }
        assertNotEquals(xid, rediscover.xid)
        assertEquals(DhcpClientState.SELECTING, client.state)
    }

    @Test
    fun `NAK discards the offer and acquisition recovers through a fresh DISCOVER`() {
        connectAndStart()
        reply(DhcpMessageType.OFFER)
        val firstXid = requests.last().xid
        reply(DhcpMessageType.NAK)

        assertEquals(DhcpClientState.INIT, client.state)
        assertNull(clientStack.config)
        scheduler.advanceBy(timers.restartDelay)
        settle()

        val discover = requests.last()
        assertEquals(DhcpMessageType.DISCOVER, discover.type)
        assertNotEquals(firstXid, discover.xid)

        reply(DhcpMessageType.OFFER)
        reply(DhcpMessageType.ACK)
        assertEquals(DhcpClientState.BOUND, client.state)
    }

    @Test
    fun `waits for the link before discovering and starts when the link comes up`() {
        client.start(WorkScope(scheduler))
        scheduler.advanceBy(30.seconds)
        assertTrue(seenByServer.isEmpty())
        assertEquals(DhcpClientState.INIT, client.state)

        cable.connect(clientNic, serverNic)
        settle()

        assertEquals(DhcpMessageType.DISCOVER, requests.single().type)
    }

    @Test
    fun `stopping while waiting for a reply cancels retries and ignores late replies`() {
        val scope = WorkScope(scheduler)
        cable.connect(clientNic, serverNic)
        client.start(scope)
        settle()

        scope.close()
        client.stop()
        reply(DhcpMessageType.OFFER)
        scheduler.advanceBy(60.seconds)

        assertEquals(1, requests.size)
        assertEquals(DhcpClientState.STOPPED, client.state)
    }

    @Test
    fun `responses addressed to a previous run are rejected after restart`() {
        cable.connect(clientNic, serverNic)
        val firstRun = WorkScope(scheduler)
        client.start(firstRun)
        settle()
        val oldDiscover = requests.last()
        firstRun.close(); client.stop()

        client.start(WorkScope(scheduler))
        settle()
        reply(DhcpMessageType.OFFER, to = oldDiscover)

        assertEquals(DhcpClientState.SELECTING, client.state)
        assertTrue(requests.none { it.type == DhcpMessageType.REQUEST })
    }

    @Test
    fun `stopping clears the volatile DHCP configuration`() {
        connectAndStart()
        reply(DhcpMessageType.OFFER)
        reply(DhcpMessageType.ACK)

        client.stop()

        assertNull(clientStack.config)
        assertEquals(DhcpClientState.STOPPED, client.state)
    }

    private fun acquire() {
        connectAndStart()
        reply(DhcpMessageType.OFFER)
        reply(DhcpMessageType.ACK)
    }

    @Test
    fun `a bound lease is untouched well before its first deadline`() {
        acquire()
        scheduler.advanceBy(1000.seconds) // well short of T1 at 1800s (50% of the 3600s lease)

        assertEquals(DhcpClientState.BOUND, client.state)
        assertEquals(offeredIp, clientStack.config?.address)
    }

    @Test
    fun `T1 sends a unicast renewal REQUEST naming the lease's server, retaining the address`() {
        acquire()
        scheduler.advanceBy(1800.seconds) // T1: 50% of the 3600s lease

        assertEquals(DhcpClientState.RENEWING, client.state)
        assertEquals(offeredIp, clientStack.config?.address, "address is retained while renewing")
        val renewal = requests.last()
        assertEquals(DhcpMessageType.REQUEST, renewal.type)
        assertEquals(serverIp, renewal.serverId)
        assertFalse(renewal.broadcast, "client already has an address and can receive a unicast reply")
    }

    @Test
    fun `a successful T1 renewal keeps the address and reschedules all deadlines`() {
        acquire()
        scheduler.advanceBy(1800.seconds) // T1
        reply(DhcpMessageType.ACK)

        assertEquals(DhcpClientState.BOUND, client.state)
        assertEquals(offeredIp, clientStack.config?.address)

        // Deadlines rescheduled from this renewal: advancing only to the *original* T2 (3150s absolute,
        // i.e. 1350s from here) must NOT yet trigger rebinding -- the new T1/T2 are relative to the renewal.
        scheduler.advanceBy(1350.seconds)
        assertEquals(DhcpClientState.BOUND, client.state, "T2 was rescheduled from the renewal, not the original bind")
    }

    @Test
    fun `failed T1 renewal attempts transition to broadcast rebinding at T2`() {
        acquire()
        scheduler.advanceBy(3150.seconds) // T2: 87.5% of the 3600s lease, with no renewal ACK in between

        assertEquals(DhcpClientState.REBINDING, client.state)
        assertEquals(offeredIp, clientStack.config?.address, "address is retained while rebinding")
        val rebind = requests.last()
        assertEquals(DhcpMessageType.REQUEST, rebind.type)
        assertTrue(rebind.broadcast)
    }

    @Test
    fun `a successful T2 rebind accepts an ACK and reschedules the lease`() {
        acquire()
        scheduler.advanceBy(3150.seconds) // T2
        reply(DhcpMessageType.ACK)

        assertEquals(DhcpClientState.BOUND, client.state)
        assertEquals(offeredIp, clientStack.config?.address)
    }

    @Test
    fun `expiry while disconnected clears the address exactly once`() {
        acquire()
        cable.disconnect()
        sink.clear()

        scheduler.advanceBy(3600.seconds) // expiry: 100% of the lease

        assertNull(clientStack.config)
        assertEquals(1, sink.of<NetworkConfigChanged>(clientNic.id).size)
        assertEquals(DhcpClientState.INIT, client.state, "link is down, so discovery doesn't resume yet")
    }

    @Test
    fun `reconnect before expiry retains the lease`() {
        acquire()
        cable.disconnect()
        scheduler.advanceBy(1800.seconds) // T1, while disconnected: renewal send fails silently
        assertEquals(DhcpClientState.RENEWING, client.state)
        assertEquals(offeredIp, clientStack.config?.address)

        cable.connect(clientNic, serverNic)
        settle()
        reply(DhcpMessageType.ACK)

        assertEquals(DhcpClientState.BOUND, client.state)
        assertEquals(offeredIp, clientStack.config?.address)
    }

    @Test
    fun `reconnect after expiry starts a fresh DISCOVER`() {
        acquire()
        cable.disconnect()
        scheduler.advanceBy(3600.seconds) // expiry while disconnected
        assertNull(clientStack.config)

        cable.connect(clientNic, serverNic)
        settle()

        assertEquals(DhcpMessageType.DISCOVER, requests.last().type)
        assertEquals(DhcpClientState.SELECTING, client.state)
    }

    @Test
    fun `a NAK during renewal clears configuration and restarts acquisition`() {
        acquire()
        scheduler.advanceBy(1800.seconds) // T1
        reply(DhcpMessageType.NAK)

        assertNull(clientStack.config)
        assertEquals(DhcpClientState.SELECTING, client.state, "link is still up, so discovery resumes immediately with no restartDelay")
        assertEquals(DhcpMessageType.DISCOVER, requests.last().type)
    }

    @Test
    fun `a NAK during rebinding clears configuration and restarts acquisition`() {
        acquire()
        scheduler.advanceBy(3150.seconds) // T2
        reply(DhcpMessageType.NAK)

        assertNull(clientStack.config)
        assertEquals(DhcpClientState.SELECTING, client.state, "link is still up, so discovery resumes immediately with no restartDelay")
        assertEquals(DhcpMessageType.DISCOVER, requests.last().type)
    }

    @Test
    fun `power-off cancels stale renewal, rebinding and expiry callbacks`() {
        val scope = WorkScope(scheduler)
        cable.connect(clientNic, serverNic)
        client.start(scope)
        settle()
        reply(DhcpMessageType.OFFER)
        reply(DhcpMessageType.ACK)

        scope.close()
        client.stop()
        val requestCountAtStop = requests.size

        scheduler.advanceBy(4000.seconds) // past T1, T2 and expiry

        assertEquals(requestCountAtStop, requests.size, "no renewal, rebind or expiry work ran after power-off")
        assertEquals(DhcpClientState.STOPPED, client.state)
    }

    // -- Snapshot: lease address, timing and deadlines --

    @Test
    fun `snapshot has no address, subnet, router or lease timing before any offer`() {
        connectAndStart()

        val state = client.snapshot().state
        assertNull(state["offeredAddress"])
        assertNull(state["subnetMask"])
        assertNull(state["router"])
        assertNull(state["leaseStartMs"])
        assertNull(state["leaseDurationMs"])
    }

    @Test
    fun `snapshot exposes address, subnet, router, server and lease timing once bound`() {
        acquire()

        val state = client.snapshot().state
        assertEquals(offeredIp.toString(), state["offeredAddress"])
        assertEquals(mask.toString(), state["subnetMask"])
        assertEquals(serverIp.toString(), state["router"])
        assertEquals(serverIp.toString(), state["selectedServer"])
        assertTrue((state["leaseStartMs"] as Long) in 0..scheduler.now.millis, "lease start is a sane virtual timestamp")
        assertEquals(3600 * 1000L, state["leaseDurationMs"])
    }

    @Test
    fun `a successful T1 renewal refreshes the lease start time`() {
        acquire()
        val boundAt = client.snapshot().state["leaseStartMs"] as Long
        scheduler.advanceBy(1800.seconds) // T1
        reply(DhcpMessageType.ACK)

        val renewedAt = client.snapshot().state["leaseStartMs"] as Long
        assertTrue(renewedAt > boundAt, "renewal refreshes the lease start time")
        assertEquals(3600 * 1000L, client.snapshot().state["leaseDurationMs"])
    }

    @Test
    fun `lease timing is cleared after a NAK during renewal, alongside the address`() {
        acquire()
        scheduler.advanceBy(1800.seconds) // T1
        reply(DhcpMessageType.NAK)

        val state = client.snapshot().state
        assertNull(state["offeredAddress"])
        assertNull(state["subnetMask"])
        assertNull(state["router"])
        assertNull(state["leaseStartMs"])
        assertNull(state["leaseDurationMs"])
    }

    @Test
    fun `lease timing is cleared once the lease expires`() {
        acquire()
        cable.disconnect()
        scheduler.advanceBy(3600.seconds) // expiry, with no link to retry over

        val state = client.snapshot().state
        assertEquals(DhcpClientState.INIT, client.state)
        assertNull(state["leaseStartMs"])
        assertNull(state["leaseDurationMs"])
    }
}
