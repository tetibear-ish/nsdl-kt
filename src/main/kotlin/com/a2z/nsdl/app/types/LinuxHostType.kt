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
import com.a2z.nsdl.ssh.SshServer
import kotlin.time.Duration.Companion.milliseconds

/** A single-interface host with DHCP and the server side of the teaching SSH protocol. */
object LinuxHostType : ObjectType {
    override val schema = ObjectTypeSchema(
        name = "linux-host",
        kind = ObjectKind.DEVICE,
        properties = listOf(
            PropertySpec("bootMs", PropertyType.LONG, required = false, default = 3000L, mutable = true, description = "Boot duration in milliseconds"),
            PropertySpec("mac", PropertyType.MAC, required = false, mutable = false, description = "Ethernet MAC address; auto-generated if omitted"),
            PropertySpec("username", PropertyType.STRING, required = false, default = "student", mutable = true, description = "SSH account username"),
            PropertySpec("password", PropertyType.STRING, required = false, default = "hunter2", mutable = true, description = "SSH account password"),
        ),
        interfaces = listOf(InterfaceSpec("eth0", MediaType.TWISTED_PAIR)),
    )

    override fun create(id: ObjectId, props: Map<String, Any?>, ctx: CreationContext): SimObject {
        val builder = HostBuilder(id, schema.name, ctx.scheduler, ctx.events)
        val mac = props["mac"] as? MacAddress ?: ctx.nextMac()
        val eth0 = builder.ethernet("eth0", mac)
        builder.service(DhcpClient(id.child("dhcp-client"), eth0, eth0, ctx.random(id.child("dhcp-client")), ctx.events, hostname = id.value))
        builder.service(SshServer(id.child("ssh-server"), eth0, username = props["username"] as String, password = props["password"] as String))

        val bootMs = props["bootMs"] as Long
        val device = builder.build { bootMs.milliseconds }
        return SimObject(
            root = device,
            components = device.interfaces + device.services,
            power = device,
            endpoints = device.interfaces,
            configView = mapOf("mac" to { mac.toString() }),
        )
    }
}
