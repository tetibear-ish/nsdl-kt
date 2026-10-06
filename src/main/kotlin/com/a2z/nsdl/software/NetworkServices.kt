package com.a2z.nsdl.software

/**
 * A host as the software layer knows it: whatever a name resolved to, handed back to
 * [NetworkServices] unchanged. Software may show it to a user but never interprets it.
 */
data class HostAddress(val text: String) {
    override fun toString() = text
}

/**
 * The result of an asynchronous operation: a value, or a reason a user could read. A [Failure] with
 * [Failure.timedOut] means the request went out but no answer came back; its reason is then the
 * likely cause (e.g. "no ARP reply from 192.168.1.101"), shown verbatim after "timed out:".
 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val reason: String, val timedOut: Boolean = false) : Outcome<Nothing>
}

/** A page as a browser receives it. A non-2xx [status] is still a document (e.g. a 404 page). */
data class WebDocument(val status: Int, val reason: String, val title: String, val body: String)

data class RemoteOutput(val text: String, val exitCode: Int)

/**
 * Everything the software layer may ask of the network, phrased in user-level terms: host names,
 * pages, documents and commands. The network layer implements it (see `com.a2z.nsdl.platform`);
 * software never sees packets, ports, interfaces, or hardware addresses.
 *
 * Every [done] callback runs at most once. Implementations may call it synchronously (e.g. a cached
 * name) or later, from simulation time; they may also never call it (e.g. a host that never
 * answers), so callers apply their own timeout and ask [diagnose] why.
 */
interface NetworkServices {
    fun resolve(name: String, done: (Outcome<HostAddress>) -> Unit)
    fun fetchPage(server: HostAddress, host: String, path: String, done: (Outcome<WebDocument>) -> Unit)
    /** [jobName] identifies the job on this host (e.g. "job-3"); success carries the printer's reply. */
    fun printDocument(printer: HostAddress, jobName: String, document: String, sizeBytes: Int, done: (Outcome<String>) -> Unit)
    fun runRemoteCommand(server: HostAddress, user: String, password: String, command: String, done: (Outcome<RemoteOutput>) -> Unit)

    /** Why [server] may not be answering, as far as this host's network can tell; null if nothing explains it. */
    fun diagnose(server: HostAddress): String?
}
