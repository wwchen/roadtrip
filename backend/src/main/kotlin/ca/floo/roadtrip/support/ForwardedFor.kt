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
 */
fun callerFromForwardedFor(chain: String?): String? =
    chain
        ?.split(',')
        ?.map { it.trim() }
        ?.lastOrNull { it.isNotEmpty() && !isPrivateNetworkLiteral(it) }

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
