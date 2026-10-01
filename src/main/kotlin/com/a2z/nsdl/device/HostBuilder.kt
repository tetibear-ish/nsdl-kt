package com.a2z.nsdl.device

import com.a2z.nsdl.ip.Ipv4Stack
import com.a2z.nsdl.link.EthernetInterface
import com.a2z.nsdl.link.MediaType
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.MacAddress
import com.a2z.nsdl.sim.Scheduler
import kotlin.time.Duration

/**
 * Assembles a [Device] from reusable networking and lifecycle pieces. This is how a new device
 * type reuses the same interface/service composition instead of each wiring it by hand.
 */
class HostBuilder(
    val id: ObjectId,
    private val type: String,
    private val scheduler: Scheduler,
    private val events: EventSink,
) {
    private val interfaces = mutableListOf<EthernetInterface>()
    private val services = mutableListOf<DeviceService>()

    fun ethernet(name: String, mac: MacAddress, media: MediaType = MediaType.TWISTED_PAIR): Ipv4Stack =
        Ipv4Stack(ethernetRaw(name, mac, media), events)

    /**
     * Adds an Ethernet interface tracked for this device's lifecycle (enabled on boot, disabled on
     * stop) without wrapping it in a single-address [Ipv4Stack]. This is the seam a multi-interface
     * device (e.g. a routed gateway composing [com.a2z.nsdl.ip.Router]) uses instead of [ethernet].
     */
    fun ethernetRaw(name: String, mac: MacAddress, media: MediaType = MediaType.TWISTED_PAIR): EthernetInterface {
        val eth = EthernetInterface(id.child(name), mac, events, media, ownerId = id)
        interfaces += eth
        return eth
    }

    fun service(s: DeviceService) {
        services += s
    }

    fun build(bootDuration: () -> Duration): Device =
        Device(id, type, interfaces.toList(), services.toList(), bootDuration, scheduler, events)
}
