package com.a2z.nsdl.app.types

import com.a2z.nsdl.app.CreationContext
import com.a2z.nsdl.app.ObjectType
import com.a2z.nsdl.app.ObjectTypeSchema
import com.a2z.nsdl.app.PropertySpec
import com.a2z.nsdl.app.PropertyType
import com.a2z.nsdl.app.SimObject
import com.a2z.nsdl.link.Cable
import com.a2z.nsdl.link.LinkProfile
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind

/**
 * A Cat5 cable. Despite the name, the declared link [LinkProfile] is an explicit property with a
 * default, never inferred from "CAT5" -- category and profile are independent in real cabling.
 */
object Cat5CableType : ObjectType {
    override val schema = ObjectTypeSchema(
        name = "cat5-cable",
        kind = ObjectKind.CABLE,
        properties = listOf(
            PropertySpec(
                "profile", PropertyType.LINK_PROFILE, required = false,
                default = LinkProfile.FAST_ETHERNET_100BASE_TX,
                mutable = true,
                description = "Declared link profile, e.g. 100BASE-TX",
            ),
        ),
    )

    override fun create(id: ObjectId, props: Map<String, Any?>, ctx: CreationContext): SimObject {
        val profile = props["profile"] as LinkProfile
        val cable = Cable(id, profile, ctx.scheduler, ctx.events, type = schema.name)
        return SimObject(root = cable, cable = cable)
    }
}
