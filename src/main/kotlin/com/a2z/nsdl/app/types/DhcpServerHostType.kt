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
import com.a2z.nsdl.link.MediaType
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.net.ConfigSource
import com.a2z.nsdl.net.Ipv4Address
import com.a2z.nsdl.net.Ipv4Config
import kotlin.time.Duration.Companion.seconds

/**
 * A single-interface host with a static address, a DHCP server serving a pool on that subnet, and a
 * DNS server for it. Clients are offered this host as their name server; the DNS server answers for
 * this host's own id and for every client that announced a host name with its lease.
 */
object DhcpServerHostType : ObjectType {
    override val schema = ObjectTypeSchema(
        name = "gateway",
        kind = ObjectKind.DEVICE,
        properties = listOf(
            PropertySpec("address", PropertyType.IPV4, required = false, default = Ipv4Address.parse("192.168.1.1"), mutable = true, description = "Static IPv4 address"),
            PropertySpec("subnetMask", PropertyType.IPV4, required = false, default = Ipv4Address.parse("255.255.255.0"), mutable = true, description = "Subnet mask"),
            PropertySpec("poolStart", PropertyType.IPV4, required = false, default = Ipv4Address.parse("192.168.1.100"), mutable = true, description = "First address in the DHCP pool"),
            PropertySpec("poolEnd", PropertyType.IPV4, required = false, default = Ipv4Address.parse("192.168.1.115"), mutable = true, description = "Last address in the DHCP pool"),
            PropertySpec("leaseSeconds", PropertyType.LONG, required = false, default = 3600L, mutable = true, description = "Lease duration in seconds"),
            PropertySpec("router", PropertyType.IPV4, required = false, default = Ipv4Address.parse("192.168.1.1"), mutable = true, description = "Router address offered to clients"),
        ),
        interfaces = listOf(InterfaceSpec("eth0", MediaType.TWISTED_PAIR)),
    )

    override fun create(id: ObjectId, props: Map<String, Any?>, ctx: CreationContext): SimObject {
        val builder = HostBuilder(id, schema.name, ctx.scheduler, ctx.events)
        val eth0 = builder.ethernet("eth0", ctx.nextMac())

        val address = props["address"] as Ipv4Address
        val subnetMask = props["subnetMask"] as Ipv4Address
        eth0.applyConfig(Ipv4Config(address, subnetMask, source = ConfigSource.STATIC))

        val pool = DhcpPool(
            start = props["poolStart"] as Ipv4Address,
            end = props["poolEnd"] as Ipv4Address,
            subnetMask = subnetMask,
            router = props["router"] as? Ipv4Address,
            leaseSeconds = props["leaseSeconds"] as Long,
            dnsServer = address,
        )
        val dns = DnsServer(id.child("dns-server"), eth0, ctx.events).apply { addStatic(id.value, address) }
        builder.service(dns)
        val serverId = id.child("dhcp-server")
        builder.service(DhcpServer(serverId, eth0, pool, ctx.events, LeasesToDns(dns)))

        val device = builder.build(bootDuration = { 1.seconds })

        return SimObject(
            root = device,
            components = device.interfaces + device.services,
            power = device,
            endpoints = device.interfaces,
            configView = mapOf(
                "address" to { address.toString() },
                "subnetMask" to { subnetMask.toString() },
                "poolStart" to { pool.start.toString() },
                "poolEnd" to { pool.end.toString() },
                "leaseSeconds" to { pool.leaseSeconds },
                "router" to { pool.router?.toString() },
            ),
        )
    }
}
