package ca.floo.roadtrip.support

import java.net.Inet6Address
import java.net.InetAddress

/** IPv6 unique-local addresses are `fc00::/7`: the first byte, masked, is `0xfc`. */
private const val ULA_FIRST_BYTE_MASK = 0xFE
private const val ULA_FIRST_BYTE = 0xFC

/**
 * The caller in an `X-Forwarded-For` chain: its rightmost entry that is not a
 * private-network hop, or null when there is none.
 *
 * Every proxy appends the address that connected to it, so a chain reads what
 * callers wrote first and what proxies wrote last. The proxy at the network's
 * edge appends the public address it was reached from; a hop inside the
 * deployment's own network (a container in front of another) appends a private
 * one. Walking in from the right past private-network addresses therefore lands
 * on what the edge saw however many inner hops there are, and never on anything
 * a caller wrote to the left of it. With no edge in front — local, tests —
 * there is no such entry, and the caller is the socket's peer.
 *
 * Entries are compared without a port (`hostAddressOf`), so a proxy that writes
 * `10.0.0.2:51234` is still skipped as private, and a caller's key does not
 * change with every connection's source port.
 */
fun callerFromForwardedFor(chain: String?): String? =
    chain
        ?.split(',')
        ?.map { hostAddressOf(it.trim()) }
        ?.lastOrNull { it.isNotEmpty() && !isPrivateNetworkLiteral(it) }

/**
 * [entry] without a port: `[2001:db8::1]:443` → `2001:db8::1`, `203.0.113.7:4711`
 * → `203.0.113.7`. A bare IPv6 literal has several colons and no port to strip,
 * and anything else is returned as it was.
 */
fun hostAddressOf(entry: String): String {
    if (entry.startsWith('[')) {
        val close = entry.indexOf(']')
        return if (close > 0) entry.substring(1, close) else entry
    }
    val colon = entry.indexOf(':')
    return if (colon > 0 && colon == entry.lastIndexOf(':')) entry.substring(0, colon) else entry
}

/**
 * Whether [value] is an IP address literal in a loopback, private, link-local or
 * unique-local range. A value that is not an address literal is not one; nothing
 * here resolves a name.
 */
fun isPrivateNetworkLiteral(value: String): Boolean {
    val address = runCatching { InetAddress.ofLiteral(value) }.getOrNull() ?: return false
    return address.isLoopbackAddress ||
        address.isSiteLocalAddress ||
        address.isLinkLocalAddress ||
        address.isAnyLocalAddress ||
        (address is Inet6Address && (address.address[0].toInt() and ULA_FIRST_BYTE_MASK) == ULA_FIRST_BYTE)
}
