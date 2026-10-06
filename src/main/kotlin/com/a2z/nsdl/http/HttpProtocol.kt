package com.a2z.nsdl.http

import com.a2z.nsdl.net.UdpPayload

/**
 * A deliberately small HTTP-*shaped* teaching protocol over UDP: one GET request and one response
 * carrying a whole page. There is no TCP connection, header set, caching, chunking, or wire
 * format, and a page is a title plus plain text -- not HTML.
 */
object HttpProtocol {
    const val SERVER_PORT = 80
    const val CLIENT_PORT = 49180
}

/** One page a [WebServer] serves. */
data class WebPage(val title: String, val body: String)

sealed interface HttpMessage : UdpPayload {
    val requestId: Int

    data class Get(override val requestId: Int, val host: String, val path: String) : HttpMessage {
        override fun describe() = "HTTP GET http://$host$path id=$requestId"
    }

    data class Response(
        override val requestId: Int,
        val status: Int,
        val reason: String,
        val page: WebPage?,
    ) : HttpMessage {
        override fun describe() = "HTTP $status $reason id=$requestId" + (page?.let { " title=\"${it.title}\"" } ?: "")
    }
}
