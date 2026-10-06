package com.a2z.nsdl.software

import com.a2z.nsdl.model.ActionOutcome
import com.a2z.nsdl.model.ObjectId

/**
 * Opens one page at a time. "open" takes a [url] such as "intranet", "intranet/about" or
 * "http://intranet/about": the host name is resolved, then the page is requested from that
 * address. Opening a new page abandons a load still in progress.
 */
class WebBrowser internal constructor(id: ObjectId, system: SystemContext) : Application(id, system) {
    enum class Status { IDLE, LOADING, LOADED, ERROR }

    override val name = "browser"
    override val actions = mapOf("open" to ::open, "reload" to ::reload)

    var status = Status.IDLE
        private set
    var url: String? = null
        private set
    var page: WebDocument? = null
        private set
    var error: String? = null
        private set
    private val history = ArrayDeque<String>()
    private var navigation = 0

    private fun open(params: Map<String, Any?>): ActionOutcome {
        val target = params.text("url") ?: return ActionOutcome(false, "missing 'url'")
        val parsed = parseUrl(target) ?: return ActionOutcome(false, "'$target' is not a web address")
        navigate(parsed)
        return ActionOutcome(true, "loading ${parsed.text}")
    }

    private fun reload(params: Map<String, Any?>): ActionOutcome {
        val current = url?.let(::parseUrl) ?: return ActionOutcome(false, "nothing to reload")
        navigate(current)
        return ActionOutcome(true, "reloading ${current.text}")
    }

    private fun navigate(target: Url) {
        val token = ++navigation
        status = Status.LOADING
        url = target.text
        page = null
        error = null
        history.addLast(target.text)
        if (history.size > HISTORY_LIMIT) history.removeFirst()
        system.emit(this, "navigate", target.text)

        system.request(target.host, null, { resolve(target.host, it) }) { resolved ->
            if (token != navigation) return@request
            when (resolved) {
                is Outcome.Failure -> fail(target, describe(resolved, "looking up ${target.host}", "can't find ${target.host}"))
                is Outcome.Success -> system.request(target.host, resolved.value, { fetchPage(resolved.value, target.host, target.path, it) }) { fetched ->
                    if (token != navigation) return@request
                    when (fetched) {
                        is Outcome.Failure -> fail(target, describe(fetched, "loading ${target.text}", "can't reach ${target.host}"))
                        is Outcome.Success -> {
                            status = Status.LOADED
                            page = fetched.value
                            system.emit(this, "loaded", "${target.text} ${fetched.value.status} ${fetched.value.title}")
                        }
                    }
                }
            }
        }
    }

    private fun fail(target: Url, reason: String) {
        status = Status.ERROR
        error = reason
        system.emit(this, "failed", "${target.text}: $reason")
    }

    override fun reset() {
        navigation++
        status = Status.IDLE
        url = null
        page = null
        error = null
        history.clear()
    }

    override fun state() = mapOf(
        "status" to status.name,
        "url" to url,
        "page" to page?.let { mapOf("status" to it.status, "reason" to it.reason, "title" to it.title, "body" to it.body) },
        "error" to error,
        "history" to history.toList(),
    )

    private data class Url(val host: String, val path: String) {
        val text get() = "http://$host$path"
    }

    private fun parseUrl(text: String): Url? {
        val rest = text.removePrefix("http://").removePrefix("https://")
        val slash = rest.indexOf('/')
        val host = (if (slash < 0) rest else rest.substring(0, slash)).lowercase()
        val path = if (slash < 0) "/" else rest.substring(slash)
        return if (host.isNotEmpty() && host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) Url(host, path) else null
    }

    private companion object {
        const val HISTORY_LIMIT = 10
    }
}
