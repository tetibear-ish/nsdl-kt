package com.a2z.nsdl.http

import com.a2z.nsdl.device.DeviceService
import com.a2z.nsdl.ip.ReceivedDatagram
import com.a2z.nsdl.ip.UdpTransport
import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.model.ObjectKind
import com.a2z.nsdl.model.ObjectSnapshot
import com.a2z.nsdl.sim.WorkScope

/** Serves a fixed set of [pages] by path; any other path is a 404. Counts requests per run. */
class WebServer(
    override val id: ObjectId,
    private val transport: UdpTransport,
    private val pages: Map<String, WebPage>,
) : DeviceService {
    private var active = false
    private var served = 0
    private var notFound = 0

    init {
        transport.bind(HttpProtocol.SERVER_PORT) { onDatagram(it) }
    }

    override fun start(scope: WorkScope) { active = true }

    override fun stop() {
        active = false
        served = 0
        notFound = 0
    }

    private fun onDatagram(d: ReceivedDatagram) {
        val get = d.datagram.payload as? HttpMessage.Get ?: return
        if (!active) return
        val page = pages[get.path.ifEmpty { "/" }]
        val response = if (page != null) {
            served++
            HttpMessage.Response(get.requestId, 200, "OK", page)
        } else {
            notFound++
            HttpMessage.Response(get.requestId, 404, "Not Found", WebPage("404 Not Found", "The page ${get.path} does not exist on ${get.host}."))
        }
        transport.sendUdp(HttpProtocol.SERVER_PORT, d.packet.src, d.datagram.srcPort, response, d.srcMac)
    }

    override fun snapshot() = ObjectSnapshot(
        id, "web-server", ObjectKind.PROTOCOL,
        state = mapOf(
            "active" to active,
            "pages" to pages.map { (path, page) -> mapOf("path" to path, "title" to page.title) },
            "served" to served,
            "notFound" to notFound,
        ),
        relations = mapOf("interface" to listOf(transport.interfaceId)),
    )
}
