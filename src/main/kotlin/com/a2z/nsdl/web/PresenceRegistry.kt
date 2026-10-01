package com.a2z.nsdl.web

/**
 * Tracks which clients currently have an open connection to the server, purely for display ("2 people
 * editing this lab") -- deliberately *not* wired into [com.a2z.nsdl.runtime.SimulationRuntime] or
 * [com.a2z.nsdl.events.EventHub]: presence is ephemeral, non-deterministic connection-liveness, never a
 * simulation event, and must never be journaled or replayed.
 *
 * A client id counts as "present" as long as at least one connection for it is open (e.g. two browser
 * tabs with the same client id). Membership is derived directly from connection lifecycle -- callers join
 * on connect and leave in a `finally` on disconnect -- rather than from a heartbeat/TTL, since an HTTP
 * connection closing is itself the liveness signal.
 *
 * Called from the HTTP server's own request-handling threads (one per open connection), so access is
 * synchronized explicitly rather than being confined to the single runtime thread.
 */
class PresenceRegistry {
    private val lock = Any()
    private val openConnections = HashMap<String, Int>()
    private val listeners = mutableListOf<(Set<String>) -> Unit>()

    fun join(clientId: String) {
        val snapshot = synchronized(lock) {
            openConnections[clientId] = (openConnections[clientId] ?: 0) + 1
            current()
        }
        notifyListeners(snapshot)
    }

    fun leave(clientId: String) {
        val snapshot = synchronized(lock) {
            val remaining = (openConnections[clientId] ?: 0) - 1
            if (remaining > 0) openConnections[clientId] = remaining else openConnections.remove(clientId)
            current()
        }
        notifyListeners(snapshot)
    }

    fun current(): Set<String> = synchronized(lock) { openConnections.keys.toSet() }

    /** Returns an unsubscribe function. */
    fun onChange(listener: (Set<String>) -> Unit): () -> Unit {
        synchronized(lock) { listeners += listener }
        return { synchronized(lock) { listeners -= listener } }
    }

    private fun notifyListeners(snapshot: Set<String>) {
        val toNotify = synchronized(lock) { listeners.toList() }
        toNotify.forEach { it(snapshot) }
    }
}
