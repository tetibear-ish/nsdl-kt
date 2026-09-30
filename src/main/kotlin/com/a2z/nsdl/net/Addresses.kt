package com.a2z.nsdl.net

import com.a2z.nsdl.model.hex

data class MacAddress(val bits: Long) {
    init { require(bits in 0..0xFFFF_FFFF_FFFFL) { "MAC out of range" } }
    val isBroadcast get() = this == BROADCAST
    override fun toString() = (5 downTo 0).joinToString(":") { hex((bits shr (it * 8)) and 0xFF, 2) }

    companion object {
        val BROADCAST = MacAddress(0xFFFF_FFFF_FFFFL)
        private val FORMAT = Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")
        fun parse(text: String): MacAddress {
            require(text.matches(FORMAT)) { "invalid MAC address '$text'" }
            return MacAddress(text.split(':').fold(0L) { acc, b -> (acc shl 8) or b.toLong(16) })
        }
        /** Locally administered unicast address, deterministic for a given index. */
        fun local(index: Int) = MacAddress(0x02_00_00_00_00_00L or (index.toLong() and 0xFF_FFFF_FFFFL))
    }
}

data class Ipv4Address(val bits: Int) {
    val isBroadcast get() = this == BROADCAST
    val isUnspecified get() = this == ANY
    operator fun plus(n: Int) = Ipv4Address(bits + n)
    fun inSubnet(other: Ipv4Address, mask: Ipv4Address) = (bits and mask.bits) == (other.bits and mask.bits)
    override fun toString() = (3 downTo 0).joinToString(".") { ((bits ushr (it * 8)) and 0xFF).toString() }

    companion object {
        val ANY = Ipv4Address(0)
        val BROADCAST = Ipv4Address(-1)
        fun parse(text: String): Ipv4Address {
            val parts = text.split('.')
            require(parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true && p.length <= 3 }) {
                "invalid IPv4 address '$text'"
            }
            return Ipv4Address(parts.fold(0) { acc, p -> (acc shl 8) or p.toInt() })
        }
    }
}

enum class ConfigSource { STATIC, DHCP }

/** IP configuration applied to an interface. */
data class Ipv4Config(
    val address: Ipv4Address,
    val subnetMask: Ipv4Address,
    val router: Ipv4Address? = null,
    val source: ConfigSource,
    val leaseSeconds: Long? = null,
    val server: Ipv4Address? = null,
)
