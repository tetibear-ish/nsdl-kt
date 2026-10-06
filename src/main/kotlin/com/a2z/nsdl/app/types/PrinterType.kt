package com.a2z.nsdl.app.types

import com.a2z.nsdl.app.CreationContext
import com.a2z.nsdl.app.InterfaceSpec
import com.a2z.nsdl.app.ObjectType
import com.a2z.nsdl.app.ObjectTypeSchema
import com.a2z.nsdl.app.PropertySpec
import com.a2z.nsdl.app.PropertyType
import com.a2z.nsdl.app.SimObject
import com.a2z.nsdl.device.HostBuilder
import com.a2z.nsdl.dhcp.DhcpClient
import com.a2z.nsdl.link.MediaType
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.print.PrintServer
import kotlin.time.Duration.Companion.milliseconds

/** A single-interface host with a DHCP client; boot time is configurable. */
object PrinterType : ObjectType {
    override val schema = ObjectTypeSchema(
        name = "printer",
        kind = ObjectKind.DEVICE,
        properties = listOf(
            PropertySpec("bootMs", PropertyType.LONG, required = false, default = 3000L, mutable = true, description = "Boot duration in milliseconds"),
            PropertySpec("mac", PropertyType.MAC, required = false, mutable = true, description = "Ethernet MAC address; auto-generated if omitted"),
        ),
        interfaces = listOf(InterfaceSpec("eth0", MediaType.TWISTED_PAIR)),
    )

    override fun create(id: ObjectId, props: Map<String, Any?>, ctx: CreationContext): SimObject {
        val builder = HostBuilder(id, schema.name, ctx.scheduler, ctx.events)
        val mac = props["mac"] as? MacAddress ?: ctx.nextMac()
        val eth0 = builder.ethernet("eth0", mac)
        val clientId = id.child("dhcp-client")
        val dhcpClient = DhcpClient(clientId, eth0, eth0, ctx.random(clientId), ctx.events, hostname = id.value)
        builder.service(dhcpClient)
        builder.service(PrintServer(id.child("print-server"), eth0))

        val bootMs = props["bootMs"] as Long
        val device = builder.build(bootDuration = { bootMs.milliseconds })

        return SimObject(
            root = device,
            components = device.interfaces + device.services,
            power = device,
            endpoints = device.interfaces,
        )
    }
}
