package com.a2z.nsdl.link

import com.a2z.nsdl.model.DropReason
import com.a2z.nsdl.model.EventPayload
import com.a2z.nsdl.model.EventSink
import com.a2z.nsdl.model.Inspectable
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.net.EthernetFrame
import com.a2z.nsdl.net.MacAddress

/**
 * Reusable Ethernet port. Owns its MAC, attachment and operational link state; hands accepted frames
 * to a bound upper layer. Physical attachment ([isAttached]) and link availability ([linkUp]) are
 * distinct: an attached interface on a powered-off device is attached but down.
 */
class EthernetInterface(
    override val id: ObjectId,
    override val mac: MacAddress,
    private val events: EventSink,
    override val media: MediaType = MediaType.TWISTED_PAIR,
    private val ownerId: ObjectId? = null,
) : LinkEndpoint, FramePort, Inspectable {
    private var medium: Medium? = null
    private var upper: FrameHandler? = null
    private var extraState: () -> Map<String, Any?> = { emptyMap() }

    override var isEnabled = false
        private set
    override var linkUp = false
        private set
    private val linkListeners = mutableListOf<(Boolean) -> Unit>()

    override val isAttached get() = medium != null

    override fun addLinkListener(listener: (Boolean) -> Unit) { linkListeners += listener }

    override fun bindUpperLayer(handler: FrameHandler) {
        check(upper == null) { "upper layer already bound to $id" }
        upper = handler
    }

    /** Lets an upper layer contribute modeled state (e.g. IPv4 configuration) to this interface's snapshot. */
    override fun contributeState(provider: () -> Map<String, Any?>) { extraState = provider }

    fun enable() = setEnabled(true)
    fun disable() = setEnabled(false)

    private fun setEnabled(value: Boolean) {
        if (isEnabled == value) return
        isEnabled = value
        medium?.endpointStateChanged() ?: refreshLinkState()
    }

    /** Hands a frame to the medium. Returns false (and reports a drop) when the link is unavailable. */
    override fun send(frame: EthernetFrame): Boolean {
        val m = medium
        if (!isEnabled || m == null || !m.isLinkUp) {
            events.emit(id, EventPayload.FrameDropped(frame, DropReason.LINK_DOWN))
            return false
        }
        events.emit(id, EventPayload.FrameSent(frame))
        m.transmit(this, frame)
        return true
    }

    override fun receive(frame: EthernetFrame) {
        when {
            !isEnabled -> events.emit(id, EventPayload.FrameDropped(frame, DropReason.RECEIVER_DISABLED))
            frame.dst != mac && !frame.dst.isBroadcast -> events.emit(id, EventPayload.FrameDropped(frame, DropReason.NOT_FOR_US))
            else -> {
                events.emit(id, EventPayload.FrameReceived(frame))
                upper?.onFrame(frame)
            }
        }
    }

    override fun attached(medium: Medium) { this.medium = medium }
    override fun detached() { medium = null }

    override fun refreshLinkState() {
        val now = isEnabled && medium?.isLinkUp == true
        if (now != linkUp) {
            linkUp = now
            events.emit(id, EventPayload.LinkStateChanged(now))
            linkListeners.forEach { it(now) }
        }
    }

    override fun snapshot() = ObjectSnapshot(
        id, "ethernet", ObjectKind.INTERFACE,
        state = mapOf(
            "mac" to mac.toString(),
            "media" to media.name,
            "enabled" to isEnabled,
            "attached" to isAttached,
            "linkUp" to linkUp,
        ) + extraState(),
        relations = buildMap {
            ownerId?.let { put("device", listOf(it)) }
            put("cable", listOfNotNull(medium?.id))
        },
    )
}
