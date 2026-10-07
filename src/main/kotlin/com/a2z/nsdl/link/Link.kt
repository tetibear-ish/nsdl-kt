package com.a2z.nsdl.link

import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.EthernetFrame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Physical connector/medium family. Endpoints connect only through a cable of the same media type. */
enum class MediaType { TWISTED_PAIR, FIBER }

/** Why a cable connection cannot be made. */
enum class ConnectionProblem { ALREADY_CONNECTED, SELF_CONNECTION, INCOMPATIBLE_MEDIA, ENDPOINT_OCCUPIED }

/** [endpoint] is the specific endpoint at fault, when the problem names one (SELF_CONNECTION, ENDPOINT_OCCUPIED). */
data class ConnectionRejection(val problem: ConnectionProblem, val endpoint: ObjectId? = null)

/**
 * Declared link behavior. Speed is informational: frames are delivered after [propagationDelay]
 * with no serialization delay, loss, bit errors, collisions or duplex negotiation.
 */
data class LinkProfile(
    val name: String,
    val media: MediaType,
    val nominalMbps: Int,
    val propagationDelay: Duration,
) {
    companion object {
        /** Point-to-point full-duplex 100BASE-TX over Category 5 twisted pair. */
        val FAST_ETHERNET_100BASE_TX = LinkProfile("100BASE-TX", MediaType.TWISTED_PAIR, 100, 1.milliseconds)
        val SUPPORTED = listOf(FAST_ETHERNET_100BASE_TX).associateBy { it.name }
    }
}

/** What a medium sees of a port. */
interface LinkEndpoint {
    val id: ObjectId
    val media: MediaType
    /** Enabled = the owning device is powered, booted and the interface is administratively up. */
    val isEnabled: Boolean
    val isAttached: Boolean
    fun attached(medium: Medium)
    fun detached()
    fun receive(frame: EthernetFrame)
    fun refreshLinkState()
}

/** What a port sees of the physical medium it is plugged into. */
interface Medium {
    val id: ObjectId
    val isLinkUp: Boolean
    fun transmit(from: LinkEndpoint, frame: EthernetFrame)
    /** An endpoint's enabled state changed; both ends must re-evaluate link availability. */
    fun endpointStateChanged()
}

/** Upper layer bound to an interface (typically an IP stack). */
fun interface FrameHandler {
    fun onFrame(frame: EthernetFrame)
}

/** What an upper layer sees of the layer-2 port beneath it. */
interface FramePort {
    val id: ObjectId
    val mac: com.a2z.nsdl.net.MacAddress
    val linkUp: Boolean
    fun send(frame: EthernetFrame): Boolean
    fun bindUpperLayer(handler: FrameHandler)
    /** In-simulation notification of operational link changes (not an external subscription). */
    fun addLinkListener(listener: (Boolean) -> Unit)
    fun contributeState(provider: () -> Map<String, Any?>)
    /** Lets an upper layer offer scriptable actions through this port's component (e.g. "clearArp"). */
    fun contributeActions(actions: com.a2z.nsdl.model.Actionable)
}
