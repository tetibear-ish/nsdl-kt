package com.a2z.nsdl.app.types

import com.a2z.nsdl.app.CreationContext
import com.a2z.nsdl.app.validateProperties
import com.a2z.nsdl.dhcp.BootOp
import com.a2z.nsdl.dhcp.DhcpMessage
import com.a2z.nsdl.dhcp.DhcpMessageType
import com.a2z.nsdl.link.EthernetInterface
import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.PowerState
import com.a2z.nsdl.net.EthernetFrame
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Packet
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.net.UdpDatagram
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class ObjectTypesCreateTest {
    private val scheduler = VirtualScheduler()
    private val sink = RecordingSink()
    private var nextMacIndex = 1
    private val ctx = CreationContext(
        scheduler = scheduler,
        events = sink,
        random = { id -> Random(id.hashCode()) },
        nextMac = { MacAddress.local(nextMacIndex++) },
    )

    @Test
    fun `creating a printer builds a device with DHCP and print services and no power yet`() {
        val validated = validateProperties(PrinterType.schema.properties, mapOf("bootMs" to 500L))
        assertTrue(validated.isValid, "errors: ${validated.errors}")

        val obj = PrinterType.create(ObjectId("printer1"), validated.properties, ctx)

        assertEquals(ObjectId("printer1"), obj.root.id)
        assertEquals(PowerState.OFF, obj.power?.powerState)
        assertEquals(1, obj.endpoints.size)
        assertEquals(3, obj.components.size, "one interface + DHCP client + print server")

        obj.power!!.powerOn()
        scheduler.advanceBy(1.seconds)
        assertEquals(PowerState.ON, obj.power.powerState)
    }

    @Test
    fun `creating a computer builds network clients and the software layer's applications`() {
        val validated = validateProperties(ComputerType.schema.properties, emptyMap())

        val obj = ComputerType.create(ObjectId("computer1"), validated.properties, ctx)

        assertEquals(ObjectId("computer1"), obj.root.id)
        assertEquals(PowerState.OFF, obj.power?.powerState)
        assertEquals(1, obj.endpoints.size)
        assertEquals(
            listOf(
                "ethernet", "dhcp-client", "print-client", "ssh-client", "dns-resolver", "web-client", "operating-system",
                "browser", "print-spooler", "terminal",
            ),
            obj.components.map { it.snapshot().type },
        )
    }

    @Test
    fun `creating a linux-host builds a device with DHCP and SSH server services`() {
        val validated = validateProperties(LinuxHostType.schema.properties, emptyMap())
        assertTrue(validated.isValid, "errors: ${validated.errors}")

        val obj = LinuxHostType.create(ObjectId("server1"), validated.properties, ctx)

        assertEquals(ObjectId("server1"), obj.root.id)
        assertEquals(PowerState.OFF, obj.power?.powerState)
        assertEquals(1, obj.endpoints.size)
        assertEquals(listOf("ethernet", "dhcp-client", "ssh-server"), obj.components.map { it.snapshot().type })
    }

    @Test
    fun `creating a printer without an explicit mac auto-generates one`() {
        val validated = validateProperties(PrinterType.schema.properties, emptyMap())
        val obj = PrinterType.create(ObjectId("printer2"), validated.properties, ctx)

        assertEquals(MacAddress.local(1), (obj.endpoints.single() as EthernetInterface).mac)
    }

    @Test
    fun `creating a gateway applies its static config and serves the declared pool`() {
        val validated = validateProperties(
            DhcpServerHostType.schema.properties,
            mapOf("address" to "10.0.0.1", "poolStart" to "10.0.0.100", "poolEnd" to "10.0.0.110"),
        )
        assertTrue(validated.isValid, "errors: ${validated.errors}")

        val obj = DhcpServerHostType.create(ObjectId("server1"), validated.properties, ctx)

        assertNotNull(obj.power)
        assertEquals(1, obj.endpoints.size)
        val snap = obj.root.snapshot()
        assertEquals("OFF", snap.state["power"])
    }

    @Test
    fun `creating a cat5-cable produces an unconnected cable with the resolved profile`() {
        val validated = validateProperties(Cat5CableType.schema.properties, emptyMap())
        val obj = Cat5CableType.create(ObjectId("cable1"), validated.properties, ctx)

        assertNotNull(obj.cable)
        assertNull(obj.power)
        assertTrue(obj.endpoints.isEmpty())
        assertEquals(false, obj.cable!!.isConnected)
        assertEquals(LinkProfile.FAST_ETHERNET_100BASE_TX, obj.cable.profile)
    }

    @Test
    fun `a cat5-cable's packet history decodes DHCP fields carried across it`() {
        val validated = validateProperties(Cat5CableType.schema.properties, emptyMap())
        val cable = Cat5CableType.create(ObjectId("cable1"), validated.properties, ctx).cable!!
        val a = EthernetInterface(ObjectId("a.eth0"), MacAddress.local(50), sink)
        val b = EthernetInterface(ObjectId("b.eth0"), MacAddress.local(51), sink)
        a.enable(); b.enable(); cable.connect(a, b)

        val discover = DhcpMessage(BootOp.REQUEST, DhcpMessageType.DISCOVER, xid = 42, chaddr = a.mac)
        a.send(EthernetFrame(a.mac, MacAddress.BROADCAST, Ipv4Packet(Ipv4Address.ANY, Ipv4Address.BROADCAST, UdpDatagram(68, 67, discover))))
        scheduler.advanceBy(LinkProfile.FAST_ETHERNET_100BASE_TX.propagationDelay)

        @Suppress("UNCHECKED_CAST")
        val packets = cable.snapshot().state["packets"] as List<Map<String, Any?>>
        val dhcp = packets.single()["dhcp"] as Map<*, *>
        assertEquals("DISCOVER", dhcp["messageType"])
    }

    @Test
    fun `creating an ethernet switch exposes eight connection ports`() {
        val validated = validateProperties(EthernetSwitchType.schema.properties, emptyMap())

        val obj = EthernetSwitchType.create(ObjectId("switch1"), validated.properties, ctx)

        assertEquals(8, obj.endpoints.size)
        assertEquals((1..8).map { ObjectId("switch1.port$it") }, obj.endpoints.map { it.id })
        assertEquals(PowerState.OFF, obj.power?.powerState)
    }
}
