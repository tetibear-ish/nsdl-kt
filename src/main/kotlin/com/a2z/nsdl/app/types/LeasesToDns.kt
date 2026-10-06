package com.a2z.nsdl.app.types

import com.a2z.nsdl.dhcp.DhcpLeaseObserver
import com.a2z.nsdl.dns.DnsServer
import com.a2z.nsdl.net.Ipv4Address

/** Keeps a [DnsServer]'s dynamic records in step with a DHCP server's named leases. */
internal class LeasesToDns(private val dns: DnsServer) : DhcpLeaseObserver {
    override fun leaseBound(hostname: String, address: Ipv4Address) = dns.register(hostname, address)
    override fun leaseReleased(hostname: String, address: Ipv4Address) = dns.unregister(hostname, address)
}
