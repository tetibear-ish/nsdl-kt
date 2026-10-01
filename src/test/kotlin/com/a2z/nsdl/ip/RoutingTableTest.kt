package com.a2z.nsdl.ip

import com.a2z.nsdl.model.ObjectId
import com.a2z.nsdl.net.Ipv4Address
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RoutingTableTest {
    private val lan = ObjectId("gw.lan")
    private val wan = ObjectId("gw.wan")

    @Test
    fun `an unmatched destination with no routes finds nothing`() {
        val table = RoutingTable()
        assertNull(table.lookup(Ipv4Address.parse("10.0.0.5")))
    }

    @Test
    fun `a connected route matches any address inside its prefix`() {
        val table = RoutingTable()
        table.add(Route(Ipv4Address.parse("10.0.0.0"), 24, nextHop = null, interfaceId = lan, kind = RouteKind.CONNECTED))

        assertEquals(lan, table.lookup(Ipv4Address.parse("10.0.0.5"))?.interfaceId)
        assertNull(table.lookup(Ipv4Address.parse("10.0.1.5")))
    }

    @Test
    fun `the longest matching prefix wins over a broader route`() {
        val table = RoutingTable()
        table.add(Route(Ipv4Address.parse("10.0.0.0"), 16, nextHop = Ipv4Address.parse("10.0.0.254"), interfaceId = wan, kind = RouteKind.STATIC))
        table.add(Route(Ipv4Address.parse("10.0.5.0"), 24, nextHop = null, interfaceId = lan, kind = RouteKind.CONNECTED))

        val route = table.lookup(Ipv4Address.parse("10.0.5.42"))
        assertEquals(lan, route?.interfaceId)
        assertEquals(24, route?.prefixLength)
    }

    @Test
    fun `a default route matches everything no more specific route covers`() {
        val table = RoutingTable()
        table.add(Route.default(nextHop = Ipv4Address.parse("203.0.113.1"), interfaceId = wan))
        table.add(Route(Ipv4Address.parse("10.0.5.0"), 24, nextHop = null, interfaceId = lan, kind = RouteKind.CONNECTED))

        assertEquals(wan, table.lookup(Ipv4Address.parse("8.8.8.8"))?.interfaceId)
        assertEquals(lan, table.lookup(Ipv4Address.parse("10.0.5.9"))?.interfaceId)
    }

    @Test
    fun `an exact host route wins over its containing subnet`() {
        val table = RoutingTable()
        table.add(Route(Ipv4Address.parse("10.0.5.0"), 24, nextHop = null, interfaceId = lan, kind = RouteKind.CONNECTED))
        table.add(Route(Ipv4Address.parse("10.0.5.9"), 32, nextHop = Ipv4Address.parse("10.0.5.1"), interfaceId = wan, kind = RouteKind.STATIC))

        assertEquals(wan, table.lookup(Ipv4Address.parse("10.0.5.9"))?.interfaceId)
    }

    @Test
    fun `a route rejects an out-of-range prefix length`() {
        val error = runCatching {
            Route(Ipv4Address.parse("10.0.0.0"), 33, nextHop = null, interfaceId = lan, kind = RouteKind.STATIC)
        }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class, error?.let { it::class })
    }
}
