package com.a2z.nsdl.app

import com.a2z.nsdl.device.PowerControl
import com.a2z.nsdl.link.Cable
import com.a2z.nsdl.link.LinkEndpoint
import com.a2z.nsdl.link.MediaType
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.Inspectable
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.sim.Scheduler
import kotlin.random.Random

/** How a property's value is validated and coerced. See [com.a2z.nsdl.app.coerce]. */
enum class PropertyType { STRING, LONG, IPV4, MAC, LINK_PROFILE }

/**
 * One property a type accepts. [default] is a fully-typed value (not a raw string) used when the
 * property is absent; it bypasses coercion, since it is trusted to already match [type].
 */
data class PropertySpec(
    val name: String,
    val type: PropertyType,
    val required: Boolean,
    val default: Any? = null,
    val mutable: Boolean = false,
    val description: String = "",
)

/** An interface a type's instances always expose, for schema discovery. */
data class InterfaceSpec(val name: String, val media: MediaType)

data class ObjectTypeSchema(
    val name: String,
    val kind: ObjectKind,
    val properties: List<PropertySpec>,
    val interfaces: List<InterfaceSpec> = emptyList(),
)

/** Per-creation dependencies an [ObjectType] needs but must not construct itself. */
class CreationContext(
    val scheduler: Scheduler,
    val events: EventSink,
    val random: (ObjectId) -> Random,
    val nextMac: () -> MacAddress,
)

/**
 * The objects and relationships created by one [ObjectType.create] call.
 * [power] and [cable] are populated only for object kinds that have them.
 */
data class SimObject(
    val root: Inspectable,
    val components: List<Inspectable> = emptyList(),
    val power: PowerControl? = null,
    val endpoints: List<LinkEndpoint> = emptyList(),
    val cable: Cable? = null,
)

/**
 * A registrable kind of simulation object. [create] assumes [props] is already validated and
 * defaulted (see [validateProperties]) against [schema]'s properties.
 */
interface ObjectType {
    val schema: ObjectTypeSchema
    fun create(id: ObjectId, props: Map<String, Any?>, ctx: CreationContext): SimObject
}
