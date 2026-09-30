package com.a2z.nsdl.link

import com.a2z.nsdl.model.DropReason
import com.a2z.nsdl.model.EventPayload.Connected
import com.a2z.nsdl.model.EventPayload.Disconnected
import com.a2z.nsdl.model.EventPayload.FrameDropped
import com.a2z.nsdl.model.EventPayload.FrameReceived
import com.a2z.nsdl.model.EventPayload.FrameSent
import com.a2z.nsdl.model.EventPayload.LinkStateChanged
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.EthernetFrame
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Packet
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.net.OpaquePayload
import com.a2z.nsdl.net.UdpDatagram
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class CableLinkTest {
    private val scheduler = VirtualScheduler()
    private val sink = RecordingSink()
    private val a = EthernetInterface(ObjectId("a.eth0"), MacAddress.local(1), sink)
    private val b = EthernetInterface(ObjectId("b.eth0"), MacAddress.local(2), sink)
    private val cable = Cable(ObjectId("cable1"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, sink)
    private val receivedByB = mutableListOf<EthernetFrame>()

    init {
        b.bindUpperLayer { receivedByB += it }
    }

    private fun frame(dst: MacAddress = b.mac, label: String = "x") = EthernetFrame(
        a.mac, dst, Ipv4Packet(Ipv4Address.ANY, Ipv4Address.BROADCAST, UdpDatagram(1, 2, OpaquePayload(label))),
    )

    private fun upLink() {
        a.enable(); b.enable(); cable.connect(a, b)
    }

    @Test
    fun `frame sent over a connected available link arrives after the propagation delay`() {
        upLink()
        val f = frame()

        assertTrue(a.send(f))
        assertTrue(receivedByB.isEmpty(), "delivery is not instantaneous")

        scheduler.advanceBy(LinkProfile.FAST_ETHERNET_100BASE_TX.propagationDelay)

        assertEquals(listOf(f), receivedByB)
        assertEquals(listOf(FrameSent(f)), sink.of<FrameSent>(a.id))
        assertEquals(listOf(FrameReceived(f)), sink.of<FrameReceived>(b.id))
    }

    @Test
    fun `connecting two enabled interfaces reports attachment then link up on both ends`() {
        a.enable(); b.enable()
        cable.connect(a, b)

        assertEquals(listOf("Connected", "LinkStateChanged", "LinkStateChanged"), sink.names())
        assertEquals(listOf(Connected(a.id, b.id)), sink.of<Connected>(cable.id))
        assertTrue(a.linkUp && b.linkUp)
    }

    @Test
    fun `attachment without an enabled peer is not an available link`() {
        a.enable()
        cable.connect(a, b)

        assertTrue(a.isAttached)
        assertFalse(a.linkUp)
        assertTrue(sink.of<LinkStateChanged>().isEmpty())

        b.enable()
        assertTrue(a.linkUp && b.linkUp)
        assertEquals(2, sink.of<LinkStateChanged>().size)
    }

    @Test
    fun `no transmission while disconnected`() {
        a.enable(); b.enable()

        assertFalse(a.send(frame()))
        scheduler.advanceBy(10.milliseconds)

        assertTrue(receivedByB.isEmpty())
        assertTrue(sink.of<FrameSent>().isEmpty())
        assertEquals(DropReason.LINK_DOWN, sink.of<FrameDropped>(a.id).single().reason)
    }

    @Test
    fun `no transmission from a disabled interface and disabling brings the link down but keeps the cable attached`() {
        upLink()
        sink.clear()

        a.disable()

        assertEquals(listOf(LinkStateChanged(false)), sink.of<LinkStateChanged>(a.id))
        assertEquals(listOf(LinkStateChanged(false)), sink.of<LinkStateChanged>(b.id))
        assertTrue(a.isAttached)
        assertFalse(a.send(frame()))
        assertFalse(b.send(frame(dst = a.mac)), "peer sees link down too")
        assertTrue(sink.of<FrameSent>().isEmpty())
    }

    @Test
    fun `frames in flight when the cable is disconnected are dropped, even if it is reconnected before arrival`() {
        upLink()
        a.send(frame(label = "lost"))
        cable.disconnect()
        cable.connect(a, b)

        scheduler.advanceBy(10.milliseconds)

        assertTrue(receivedByB.isEmpty())
        assertEquals(DropReason.DISCONNECTED_IN_FLIGHT, sink.of<FrameDropped>(cable.id).single().reason)
    }

    @Test
    fun `frame arriving at a receiver that was disabled while it was in flight is dropped`() {
        upLink()
        a.send(frame())
        b.disable()

        scheduler.advanceBy(10.milliseconds)

        assertTrue(receivedByB.isEmpty())
        assertEquals(DropReason.RECEIVER_DISABLED, sink.of<FrameDropped>(b.id).single().reason)
    }

    @Test
    fun `receiver filters frames by destination MAC but accepts broadcast`() {
        upLink()
        a.send(frame(dst = MacAddress.local(99)))
        a.send(frame(dst = MacAddress.BROADCAST))
        scheduler.advanceBy(10.milliseconds)

        assertEquals(listOf(MacAddress.BROADCAST), receivedByB.map { it.dst })
        assertEquals(DropReason.NOT_FOR_US, sink.of<FrameDropped>(b.id).single().reason)
    }

    @Test
    fun `disconnect reports detachment then link down`() {
        upLink()
        sink.clear()

        cable.disconnect()

        assertEquals(listOf("Disconnected", "LinkStateChanged", "LinkStateChanged"), sink.names())
        assertEquals(listOf(Disconnected(a.id, b.id)), sink.of<Disconnected>(cable.id))
        assertFalse(a.isAttached)
    }

    @Test
    fun `two enabled interfaces with no conflict have no connection problem`() {
        a.enable(); b.enable()
        assertNull(cable.connectionProblem(a, b))
    }

    @Test
    fun `connecting to an already-connected cable is rejected as ALREADY_CONNECTED`() {
        upLink()
        val c = EthernetInterface(ObjectId("c.eth0"), MacAddress.local(3), sink)

        assertEquals(ConnectionRejection(ConnectionProblem.ALREADY_CONNECTED), cable.connectionProblem(a, c))
    }

    @Test
    fun `connecting an endpoint to itself is rejected as SELF_CONNECTION`() {
        val aAgain = EthernetInterface(a.id, MacAddress.local(9), sink)

        assertEquals(ConnectionRejection(ConnectionProblem.SELF_CONNECTION, a.id), cable.connectionProblem(a, aAgain))
    }

    @Test
    fun `connecting to an occupied endpoint is rejected as ENDPOINT_OCCUPIED, naming the occupied one`() {
        upLink()
        val fresh = Cable(ObjectId("cable2"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, sink)
        val c = EthernetInterface(ObjectId("c.eth0"), MacAddress.local(3), sink)

        assertEquals(ConnectionRejection(ConnectionProblem.ENDPOINT_OCCUPIED, a.id), fresh.connectionProblem(a, c))
        assertEquals(ConnectionRejection(ConnectionProblem.ENDPOINT_OCCUPIED, b.id), fresh.connectionProblem(c, b))
    }

    @Test
    fun `connect throws for a rejected connection`() {
        upLink()
        val c = EthernetInterface(ObjectId("c.eth0"), MacAddress.local(3), sink)

        assertThrows(IllegalStateException::class.java) { cable.connect(a, c) }
    }

    @Test
    fun `snapshots expose attachment and link state with relationships as ids`() {
        upLink()
        val snap = a.snapshot()
        assertEquals(true, snap.state["linkUp"])
        assertEquals(a.mac.toString(), snap.state["mac"])
        assertEquals(listOf(cable.id), snap.relations["cable"])
        assertEquals(listOf(a.id, b.id), cable.snapshot().relations["endpoints"])
    }
}
