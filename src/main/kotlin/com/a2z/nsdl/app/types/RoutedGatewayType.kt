package com.a2z.nsdl.app.types

import com.a2z.nsdl.app.CreationContext
import com.a2z.nsdl.app.InterfaceSpec
import com.a2z.nsdl.app.ObjectType
import com.a2z.nsdl.app.ObjectTypeSchema
import com.a2z.nsdl.app.PropertySpec
import com.a2z.nsdl.app.PropertyType
import com.a2z.nsdl.app.SimObject
import com.a2z.nsdl.device.HostBuilder
import com.a2z.nsdl.dhcp.DhcpPool
import com.a2z.nsdl.dhcp.DhcpServer
import com.a2z.nsdl.dns.DnsServer
import com.a2z.nsdl.ip.Route
import com.a2z.nsdl.ip.Router
import com.a2z.nsdl.link.MediaType
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.net.ConfigSource
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import kotlin.time.Duration.Companion.milliseconds

/**
 * A composable multi-interface IPv4 gateway: a "lan" and a "wan" interface, both carried by one
 * [Router], with directly connected routes for whichever interfaces have an address configured.
 *
 * The lone-interface, DHCP-serving gateway this type replaces ([DhcpServerHostType]) is still
 * registered unchanged (see its own file for why); this type is additive. Its own single-interface
 * special case is: configure only "lanAddress"/"lanSubnetMask" and a DHCP pool, and leave every "wan*"
 * property at its default (null/unconfigured) -- the wan interface then exists as a connectable port
 * with no address, no connected route and no service bound to it.
 *
 * - DHCP is optional and bound only to the lan interface's transport ([poolStart]/[poolEnd] both set);
 *   a DNS server for the lan comes with it, offered to clients and fed by their named leases.
 * - The wan interface is optional ([wanAddress] unset leaves it unconfigured).
 * - A default route out wan is installed only when both [wanAddress] and [wanGateway] are set.
 */
object RoutedGatewayType : ObjectType {
    override val schema = ObjectTypeSchema(
        name = "routed-gateway",
        kind = ObjectKind.DEVICE,
        properties = listOf(
            PropertySpec("bootMs", PropertyType.LONG, required = false, default = 1000L, mutable = true, description = "Boot duration in milliseconds"),
            PropertySpec("lanAddress", PropertyType.IPV4, required = false, default = Ipv4Address.parse("192.168.1.1"), mutable = true, description = "Static IPv4 address on the lan interface"),
            PropertySpec("lanSubnetMask", PropertyType.IPV4, required = false, default = Ipv4Address.parse("255.255.255.0"), mutable = true, description = "Subnet mask on the lan interface"),
            PropertySpec("wanAddress", PropertyType.IPV4, required = false, default = null, mutable = true, description = "Static IPv4 address on the wan interface; unset leaves it unconfigured"),
            PropertySpec("wanSubnetMask", PropertyType.IPV4, required = false, default = Ipv4Address.parse("255.255.255.0"), mutable = true, description = "Subnet mask on the wan interface"),
            PropertySpec("wanGateway", PropertyType.IPV4, required = false, default = null, mutable = true, description = "Next hop for a default route out wan; requires wanAddress"),
            PropertySpec("poolStart", PropertyType.IPV4, required = false, default = null, mutable = true, description = "First address of the DHCP pool served on lan; requires poolEnd"),
            PropertySpec("poolEnd", PropertyType.IPV4, required = false, default = null, mutable = true, description = "Last address of the DHCP pool served on lan; requires poolStart"),
            PropertySpec("leaseSeconds", PropertyType.LONG, required = false, default = 3600L, mutable = true, description = "DHCP lease duration in seconds"),
        ),
        interfaces = listOf(InterfaceSpec("lan", MediaType.TWISTED_PAIR), InterfaceSpec("wan", MediaType.TWISTED_PAIR)),
    )

    override fun create(id: ObjectId, props: Map<String, Any?>, ctx: CreationContext): SimObject {
        val builder = HostBuilder(id, schema.name, ctx.scheduler, ctx.events)
        val lan = builder.ethernetRaw("lan", ctx.nextMac())
        val wan = builder.ethernetRaw("wan", ctx.nextMac())
        val router = Router(id.child("router"), listOf(lan, wan), ctx.events)

        val lanAddress = props["lanAddress"] as Ipv4Address
        val lanSubnetMask = props["lanSubnetMask"] as Ipv4Address
        router.configure(lan.id, Ipv4Config(lanAddress, lanSubnetMask, source = ConfigSource.STATIC))

        val wanAddress = props["wanAddress"] as? Ipv4Address
        if (wanAddress != null) {
            val wanSubnetMask = props["wanSubnetMask"] as Ipv4Address
            router.configure(wan.id, Ipv4Config(wanAddress, wanSubnetMask, source = ConfigSource.STATIC))
            val wanGateway = props["wanGateway"] as? Ipv4Address
            if (wanGateway != null) router.addRoute(Route.default(wanGateway, wan.id))
        }

        val poolStart = props["poolStart"] as? Ipv4Address
        val poolEnd = props["poolEnd"] as? Ipv4Address
        if (poolStart != null && poolEnd != null) {
            val lanTransport = router.transport(lan.id)
            val pool = DhcpPool(poolStart, poolEnd, lanSubnetMask, router = lanAddress, leaseSeconds = props["leaseSeconds"] as Long, dnsServer = lanAddress)
            val dns = DnsServer(id.child("dns-server"), lanTransport, ctx.events).apply { addStatic(id.value, lanAddress) }
            builder.service(dns)
            builder.service(DhcpServer(id.child("dhcp-server"), lanTransport, pool, ctx.events, LeasesToDns(dns)))
        }

        val device = builder.build { (props["bootMs"] as Long).milliseconds }
        return SimObject(
            root = device,
            components = device.interfaces + device.services + listOf(router),
            power = device,
            endpoints = device.interfaces,
        )
    }
}
