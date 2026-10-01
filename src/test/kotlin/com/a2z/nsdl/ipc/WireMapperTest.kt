package com.a2z.nsdl.ipc

import com.a2z.nsdl.ip.toState
import com.a2z.nsdl.model.DropReason
import com.a2z.nsdl.model.DecisionAction
import com.a2z.nsdl.model.DecisionParents
import com.a2z.nsdl.model.DecisionRecord
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.PowerState
import com.a2z.nsdl.model.Responsibility
import com.a2z.nsdl.net.ConfigSource
import com.a2z.nsdl.net.EthernetFrame
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import com.a2z.nsdl.net.Ipv4Packet
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.net.OpaquePayload
import com.a2z.nsdl.net.UdpDatagram
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WireMapperTest {
    private val mac1 = MacAddress.local(1)
    private val mac2 = MacAddress.local(2)
    private val frame = EthernetFrame(mac1, mac2, Ipv4Packet(Ipv4Address.ANY, Ipv4Address.BROADCAST, UdpDatagram(1, 2, OpaquePayload("x"))))
    private val packet = frame.payload as Ipv4Packet

    @Test
    fun `maps ObjectCreated`() {
        assertEquals(
            mapOf("type" to "printer", "kind" to "DEVICE"),
            WireMapper.toData(EventPayload.ObjectCreated("printer", ObjectKind.DEVICE)),
        )
    }

    @Test
    fun `maps PowerOnStarted`() {
        assertEquals(mapOf("generation" to 1L), WireMapper.toData(EventPayload.PowerOnStarted(1)))
    }

    @Test
    fun `maps BootCompleted`() {
        assertEquals(mapOf("generation" to 1L), WireMapper.toData(EventPayload.BootCompleted(1)))
    }

    @Test
    fun `maps PoweredOff`() {
        assertEquals(
            mapOf("generation" to 1L, "previous" to "ON"),
            WireMapper.toData(EventPayload.PoweredOff(1, PowerState.ON)),
        )
    }

    @Test
    fun `maps Connected`() {
        assertEquals(
            mapOf("endpointA" to "a.eth0", "endpointB" to "b.eth0"),
            WireMapper.toData(EventPayload.Connected(ObjectId("a.eth0"), ObjectId("b.eth0"))),
        )
    }

    @Test
    fun `maps Disconnected`() {
        assertEquals(
            mapOf("endpointA" to "a.eth0", "endpointB" to "b.eth0"),
            WireMapper.toData(EventPayload.Disconnected(ObjectId("a.eth0"), ObjectId("b.eth0"))),
        )
    }

    @Test
    fun `maps LinkStateChanged`() {
        assertEquals(mapOf("up" to true), WireMapper.toData(EventPayload.LinkStateChanged(true)))
    }

    @Test
    fun `maps FrameSent using the frame's own description`() {
        assertEquals(mapOf("frame" to frame.describe()), WireMapper.toData(EventPayload.FrameSent(frame)))
    }

    @Test
    fun `maps FrameReceived using the frame's own description`() {
        assertEquals(mapOf("frame" to frame.describe()), WireMapper.toData(EventPayload.FrameReceived(frame)))
    }

    @Test
    fun `maps FrameDropped with its reason`() {
        assertEquals(
            mapOf("frame" to frame.describe(), "reason" to "LINK_DOWN"),
            WireMapper.toData(EventPayload.FrameDropped(frame, DropReason.LINK_DOWN)),
        )
    }

    @Test
    fun `maps PacketAccepted using the packet's own description`() {
        assertEquals(mapOf("packet" to packet.describe()), WireMapper.toData(EventPayload.PacketAccepted(packet)))
    }

    @Test
    fun `maps ProtocolStateChanged`() {
        assertEquals(
            mapOf("protocol" to "dhcp-client", "from" to "INIT", "to" to "SELECTING", "detail" to "xid=1"),
            WireMapper.toData(EventPayload.ProtocolStateChanged("dhcp-client", "INIT", "SELECTING", "xid=1")),
        )
    }

    @Test
    fun `maps NetworkConfigChanged with a config, reusing Ipv4Config's own wire state`() {
        val config = Ipv4Config(Ipv4Address.parse("10.0.0.1"), Ipv4Address.parse("255.255.255.0"), source = ConfigSource.STATIC)
        assertEquals(mapOf("config" to config.toState()), WireMapper.toData(EventPayload.NetworkConfigChanged(config)))
    }

    @Test
    fun `maps NetworkConfigChanged with a null config`() {
        assertEquals(mapOf("config" to null), WireMapper.toData(EventPayload.NetworkConfigChanged(null)))
    }

    @Test
    fun `maps ConfigurationChanged`() {
        assertEquals(
            mapOf("properties" to mapOf("bootMs" to 500L)),
            WireMapper.toData(EventPayload.ConfigurationChanged(mapOf("bootMs" to 500L))),
        )
    }

    @Test
    fun `maps DecisionRecorded with causal identifiers without reconstructing it`() {
        val record = DecisionRecord(
            id = "switch1:decision:7",
            responsibility = Responsibility.SWITCHING,
            decision = DecisionAction.FORWARD,
            reason = "destination learned on egress port",
            parents = DecisionParents(
                intentionId = "intent-1",
                processId = "ssh-1",
                sessionId = "tcp-1",
                exchangeId = "handshake-1",
                packetId = "packet-3",
            ),
            attributes = mapOf("egress" to "switch1.port2"),
        )

        assertEquals(mapOf("record" to record.toState()), WireMapper.toData(EventPayload.DecisionRecorded(record)))
    }

    @Test
    fun `maps ActionPerformed`() {
        assertEquals(
            mapOf("action" to "submit", "accepted" to true, "detail" to "queued"),
            WireMapper.toData(EventPayload.ActionPerformed("submit", true, "queued")),
        )
    }
}
