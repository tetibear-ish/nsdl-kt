package com.a2z.nsdl.ip

import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.Ipv4Address

/** How a route entered the table. Purely descriptive; matching treats every kind alike. */
enum class RouteKind { CONNECTED, STATIC, DEFAULT }

/**
 * One IPv4 routing table entry. [nextHop] is informational only: there is no ARP in this model, so a
 * forwarded frame always addresses the egress medium with a broadcast destination MAC and relies on
 * the receiver's own IPv4 address match (see [Ipv4Stack]); [nextHop] still records what a real router
 * would have resolved, for inspection and decision logs.
 */
data class Route(
    val destination: Ipv4Address,
    val prefixLength: Int,
    val nextHop: Ipv4Address?,
    val interfaceId: ObjectId,
    val kind: RouteKind,
) {
    init { require(prefixLength in 0..32) { "prefix length must be 0..32, was $prefixLength" } }

    /** Whether [dst] falls inside this route's prefix. A /0 route (the default route) matches everything. */
    fun matches(dst: Ipv4Address): Boolean {
        if (prefixLength == 0) return true
        val mask = -1 shl (32 - prefixLength)
        return (dst.bits and mask) == (destination.bits and mask)
    }

    fun toState(): Map<String, Any?> = mapOf(
        "destination" to destination.toString(),
        "prefixLength" to prefixLength,
        "nextHop" to nextHop?.toString(),
        "interface" to interfaceId.value,
        "kind" to kind.name,
    )

    companion object {
        /** The 0.0.0.0/0 route: used only when nothing more specific matches. */
        fun default(nextHop: Ipv4Address?, interfaceId: ObjectId) =
            Route(Ipv4Address.ANY, 0, nextHop, interfaceId, RouteKind.DEFAULT)
    }
}

/**
 * A flat set of [Route]s resolved by longest-prefix match, the standard IPv4 forwarding rule: the
 * most specific matching route wins regardless of the order routes were added.
 */
class RoutingTable {
    private val routes = mutableListOf<Route>()

    fun add(route: Route) {
        routes += route
    }

    fun all(): List<Route> = routes.toList()

    fun lookup(dst: Ipv4Address): Route? =
        routes.filter { it.matches(dst) }.maxByOrNull { it.prefixLength }
}
