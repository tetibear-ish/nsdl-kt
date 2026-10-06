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
import com.a2z.nsdl.http.WebPage
import com.a2z.nsdl.http.WebServer
import com.a2z.nsdl.link.MediaType
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.net.MacAddress
import kotlin.time.Duration.Companion.milliseconds

/**
 * A single-interface host serving a small site over the teaching web protocol: "/" (from [title] and
 * [homeText]) and "/about". It takes its address from DHCP and announces its id as host name, so
 * browsers on the network can open "http://<id>/".
 */
object WebServerType : ObjectType {
    override val schema = ObjectTypeSchema(
        name = "web-server",
        kind = ObjectKind.DEVICE,
        properties = listOf(
            PropertySpec("bootMs", PropertyType.LONG, required = false, default = 2000L, mutable = true, description = "Boot duration in milliseconds"),
            PropertySpec("mac", PropertyType.MAC, required = false, mutable = true, description = "Ethernet MAC address; auto-generated if omitted"),
            PropertySpec("title", PropertyType.STRING, required = false, default = "Intranet", mutable = true, description = "Title of the home page"),
            PropertySpec("homeText", PropertyType.STRING, required = false, default = "Welcome! This page was served over the simulated network.", mutable = true, description = "Body text of the home page"),
        ),
        interfaces = listOf(InterfaceSpec("eth0", MediaType.TWISTED_PAIR)),
    )

    override fun create(id: ObjectId, props: Map<String, Any?>, ctx: CreationContext): SimObject {
        val builder = HostBuilder(id, schema.name, ctx.scheduler, ctx.events)
        val eth0 = builder.ethernet("eth0", props["mac"] as? MacAddress ?: ctx.nextMac())
        val dhcpId = id.child("dhcp-client")
        builder.service(DhcpClient(dhcpId, eth0, eth0, ctx.random(dhcpId), ctx.events, hostname = id.value))
        val pages = mapOf(
            "/" to WebPage(props["title"] as String, props["homeText"] as String),
            "/about" to WebPage("About ${id.value}", "${id.value} is a simulated web server on this network."),
        )
        builder.service(WebServer(id.child("web-server"), eth0, pages))

        val device = builder.build { (props["bootMs"] as Long).milliseconds }
        return SimObject(
            root = device,
            components = device.interfaces + device.services,
            power = device,
            endpoints = device.interfaces,
        )
    }
}
