package com.a2z.nsdl.device

import com.a2z.nsdl.ip.Router
import com.a2z.nsdl.link.Cable
import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.PowerState
import com.a2z.nsdl.net.ConfigSource
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.net.OpaquePayload
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds

/**
 * Demonstrates how a multi-interface routed gateway reuses HostBuilder/Device composition and power
 * lifecycle: several raw interfaces attached through [HostBuilder.ethernetRaw] and wrapped by one
 * [Router], instead of the single-interface [HostBuilder.ethernet] a plain host uses.
 */
class RoutedGatewayDeviceTest {
    private val scheduler = VirtualScheduler()
    private val events = RecordingSink()

    @Test
    fun `a gateway device enables both interfaces on boot and disables both on power-off`() {
        lateinit var router: Router
        val gateway = HostBuilder(ObjectId("gw"), "routed-gateway", scheduler, events).run {
            val lan = ethernetRaw("lan", MacAddress.local(1))
            val wan = ethernetRaw("wan", MacAddress.local(2))
            router = Router(id.child("router"), listOf(lan, wan), events)
            build { ZERO }
        }

        assertEquals(2, gateway.interfaces.size)
        assertTrue(gateway.interfaces.none { it.isEnabled })

        gateway.powerOn()
        scheduler.advanceBy(ZERO)
        assertTrue(gateway.interfaces.all { it.isEnabled })

        gateway.powerOff()
        assertTrue(gateway.interfaces.none { it.isEnabled })
        assertEquals(PowerState.OFF, gateway.powerState)
    }

    @Test
    fun `forwarding only works while the device is powered on`() {
        lateinit var router: Router
        val gateway = HostBuilder(ObjectId("gw"), "routed-gateway", scheduler, events).run {
            val lan = ethernetRaw("lan", MacAddress.local(1))
            val wan = ethernetRaw("wan", MacAddress.local(2))
            router = Router(id.child("router"), listOf(lan, wan), events)
            router.configure(lan.id, Ipv4Config(Ipv4Address.parse("10.0.1.1"), Ipv4Address.parse("255.255.255.0"), source = ConfigSource.STATIC))
            router.configure(wan.id, Ipv4Config(Ipv4Address.parse("10.0.2.1"), Ipv4Address.parse("255.255.255.0"), source = ConfigSource.STATIC))
            build { ZERO }
        }
        val host = HostBuilder(ObjectId("host"), "host", scheduler, events).run {
            val eth0 = ethernet("eth0", MacAddress.local(3))
            eth0.applyConfig(Ipv4Config(Ipv4Address.parse("10.0.1.10"), Ipv4Address.parse("255.255.255.0"), source = ConfigSource.STATIC))
            build { ZERO } to eth0
        }
        val (hostDevice, hostStack) = host
        Cable(ObjectId("c1"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, events).connect(gateway.interfaces[0], hostDevice.interfaces.single())
        hostDevice.powerOn()
        scheduler.advanceBy(10.milliseconds)

        // The gateway is still off, so the link never comes up: nothing leaves the host's own interface.
        val sentWhileOff = hostStack.sendUdp(1, Ipv4Address.parse("10.0.2.10"), 1, OpaquePayload("x"), dstMac = gateway.interfaces[0].mac)
        scheduler.advanceBy(10.milliseconds)
        assertTrue(!sentWhileOff)
        assertTrue(events.of<EventPayload.DecisionRecorded>(router.id).isEmpty())

        gateway.powerOn()
        scheduler.advanceBy(10.milliseconds)

        val sentWhileOn = hostStack.sendUdp(1, Ipv4Address.parse("10.0.2.10"), 1, OpaquePayload("y"), dstMac = gateway.interfaces[0].mac)
        scheduler.advanceBy(10.milliseconds)
        assertTrue(sentWhileOn)
        assertEquals(1, events.of<EventPayload.DecisionRecorded>(router.id).size, "the booted gateway now routes the frame")
    }
}
