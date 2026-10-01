package com.a2z.nsdl.app.types

import com.a2z.nsdl.app.CreationContext
import com.a2z.nsdl.app.InterfaceSpec
import com.a2z.nsdl.app.ObjectType
import com.a2z.nsdl.app.ObjectTypeSchema
import com.a2z.nsdl.app.PropertySpec
import com.a2z.nsdl.app.PropertyType
import com.a2z.nsdl.app.SimObject
import com.a2z.nsdl.device.EthernetSwitch
import com.a2z.nsdl.link.MediaType
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import kotlin.time.Duration.Companion.milliseconds

/** A powered eight-port learning Ethernet switch. */
object EthernetSwitchType : ObjectType {
    override val schema = ObjectTypeSchema(
        name = "ethernet-switch",
        kind = ObjectKind.DEVICE,
        properties = listOf(
            PropertySpec("bootMs", PropertyType.LONG, required = false, default = 0L, mutable = true, description = "Boot duration in milliseconds"),
        ),
        interfaces = (1..8).map { InterfaceSpec("port$it", MediaType.TWISTED_PAIR) },
    )

    override fun create(id: ObjectId, props: Map<String, Any?>, ctx: CreationContext): SimObject {
        val switch = EthernetSwitch(
            id,
            portCount = 8,
            scheduler = ctx.scheduler,
            events = ctx.events,
            bootDuration = (props["bootMs"] as Long).milliseconds,
        )
        return SimObject(
            root = switch,
            components = switch.ports,
            power = switch,
            endpoints = switch.ports,
        )
    }
}
