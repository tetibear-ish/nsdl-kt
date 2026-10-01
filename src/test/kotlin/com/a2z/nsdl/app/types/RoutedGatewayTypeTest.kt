package com.a2z.nsdl.app.types

import com.a2z.nsdl.app.CreationContext
import com.a2z.nsdl.app.validateProperties
import com.a2z.nsdl.dhcp.DhcpClient
import com.a2z.nsdl.ip.Ipv4Stack
import com.a2z.nsdl.ip.Router
import com.a2z.nsdl.ip.RouteKind
import com.a2z.nsdl.link.Cable
import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.PowerState
import com.a2z.nsdl.net.ConfigSource
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.sim.VirtualScheduler
import com.a2z.nsdl.testing.RecordingSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class RoutedGatewayTypeTest {
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
    fun `the schema always exposes a lan and a wan interface`() {
        assertEquals(listOf("lan", "wan"), RoutedGatewayType.schema.interfaces.map { it.name })
    }

    @Test
    fun `defaults configure only the lan interface, leaving wan unconfigured and no DHCP service`() {
        val validated = validateProperties(RoutedGatewayType.schema.properties, emptyMap())
        assertTrue(validated.isValid, "errors: ${validated.errors}")

        val obj = RoutedGatewayType.create(ObjectId("gw1"), validated.properties, ctx)

        assertEquals(2, obj.endpoints.size)
        assertEquals(PowerState.OFF, obj.power?.powerState)
        // interfaces (2) + router (1), no DHCP service since no pool was configured
        assertEquals(3, obj.components.size)
    }

    @Test
    fun `DHCP is served on lan only, and unreachable from a host cabled to wan`() {
        val validated = validateProperties(
            RoutedGatewayType.schema.properties,
            mapOf(
                "lanAddress" to "10.0.1.1", "poolStart" to "10.0.1.100", "poolEnd" to "10.0.1.110",
                "wanAddress" to "10.0.2.1",
            ),
        )
        assertTrue(validated.isValid, "errors: ${validated.errors}")
        val gw = RoutedGatewayType.create(ObjectId("gw1"), validated.properties, ctx)

        lateinit var lanClientStack: Ipv4Stack
        val lanClient = com.a2z.nsdl.device.HostBuilder(ObjectId("lan-client"), "host", ctx.scheduler, ctx.events).run {
            lanClientStack = ethernet("eth0", ctx.nextMac())
            service(DhcpClient(id.child("dhcp-client"), lanClientStack, lanClientStack, Random(1), ctx.events))
            build { kotlin.time.Duration.ZERO }
        }
        lateinit var wanHostStack: Ipv4Stack
        val wanHost = com.a2z.nsdl.device.HostBuilder(ObjectId("wan-host"), "host", ctx.scheduler, ctx.events).run {
            wanHostStack = ethernet("eth0", ctx.nextMac())
            service(DhcpClient(id.child("dhcp-client"), wanHostStack, wanHostStack, Random(2), ctx.events))
            build { kotlin.time.Duration.ZERO }
        }

        val lan = gw.endpoints[0]
        val wan = gw.endpoints[1]
        Cable(ObjectId("c-lan"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, sink).connect(lan, lanClient.interfaces.single())
        Cable(ObjectId("c-wan"), LinkProfile.FAST_ETHERNET_100BASE_TX, scheduler, sink).connect(wan, wanHost.interfaces.single())

        gw.power!!.powerOn()
        lanClient.powerOn()
        wanHost.powerOn()
        scheduler.advanceBy(5.seconds)

        assertNotNull(lanClientStack.config, "the lan client should receive a DHCP lease")
        assertEquals(ConfigSource.DHCP, lanClientStack.config?.source)
        assertNull(wanHostStack.config, "the wan host must not be served by the lan-only DHCP service")
    }

    @Test
    fun `a wan gateway address installs a default route out wan`() {
        val validated = validateProperties(
            RoutedGatewayType.schema.properties,
            mapOf("wanAddress" to "203.0.113.1", "wanGateway" to "203.0.113.254"),
        )
        val gw = RoutedGatewayType.create(ObjectId("gw1"), validated.properties, ctx)

        val router = gw.components.filterIsInstance<Router>().single()
        val default = router.routes().single { it.kind == RouteKind.DEFAULT }
        assertEquals(Ipv4Address.parse("203.0.113.254"), default.nextHop)
    }

    @Test
    fun `without a wan gateway address no default route is installed even if wan has an address`() {
        val validated = validateProperties(RoutedGatewayType.schema.properties, mapOf("wanAddress" to "203.0.113.1"))
        val gw = RoutedGatewayType.create(ObjectId("gw1"), validated.properties, ctx)

        val router = gw.components.filterIsInstance<Router>().single()
        assertTrue(router.routes().none { it.kind == RouteKind.DEFAULT })
        assertTrue(router.routes().any { it.kind == RouteKind.CONNECTED })
    }
}
