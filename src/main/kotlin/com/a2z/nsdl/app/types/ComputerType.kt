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
import com.a2z.nsdl.print.PrintClient
import kotlin.time.Duration.Companion.milliseconds

/** A general endpoint host with DHCP and the client side of the teaching print protocol. */
object ComputerType : ObjectType {
    override val schema = ObjectTypeSchema(
        name = "computer",
        kind = ObjectKind.DEVICE,
        properties = listOf(
            PropertySpec("bootMs", PropertyType.LONG, required = false, default = 3000L, mutable = true, description = "Boot duration in milliseconds"),
            PropertySpec("mac", PropertyType.MAC, required = false, description = "Ethernet MAC address; auto-generated if omitted"),
        ),
        interfaces = listOf(InterfaceSpec("eth0", MediaType.TWISTED_PAIR)),
    )

    override fun create(id: ObjectId, props: Map<String, Any?>, ctx: CreationContext): SimObject {
        val builder = HostBuilder(id, schema.name, ctx.scheduler, ctx.events)
        val eth0 = builder.ethernet("eth0", props["mac"] as? MacAddress ?: ctx.nextMac())
        val dhcpId = id.child("dhcp-client")
        builder.service(DhcpClient(dhcpId, eth0, eth0, ctx.random(dhcpId), ctx.events))
        builder.service(PrintClient(id.child("print-client"), eth0))

        var bootMs = props["bootMs"] as Long
        val device = builder.build { bootMs.milliseconds }
        return SimObject(
            root = device,
            components = device.interfaces + device.services,
            power = device,
            endpoints = device.interfaces,
            configure = { name, value -> if (name == "bootMs") bootMs = value as Long },
        )
    }
}
