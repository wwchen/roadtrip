package ca.floo.roadtrip.support

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForwardedForTest {
    @Test
    fun `the caller is the rightmost entry past private-network hops`() {
        // A caller-written entry, the edge's, then a container's appended inside the network.
        assertEquals("203.0.113.7", callerFromForwardedFor("192.0.2.99, 203.0.113.7, 172.18.0.5"))
        assertEquals("203.0.113.7", callerFromForwardedFor("203.0.113.7, 10.0.0.2, 127.0.0.1"))
        assertEquals("2001:db8::7", callerFromForwardedFor("2001:db8::7, fd00::1"))
    }

    @Test
    fun `with only private hops, or no header, there is no forwarded caller`() {
        assertNull(callerFromForwardedFor("10.0.0.2, 192.168.1.4"))
        assertNull(callerFromForwardedFor(" , "))
        assertNull(callerFromForwardedFor(null))
    }

    @Test
    fun `the private ranges are loopback, site-local, link-local and unique-local`() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.1.1", "::1", "fe80::1", "fc00::1", "fd12::1", "0.0.0.0")
            .forEach { assertTrue(isPrivateNetworkLiteral(it), it) }
        listOf("203.0.113.7", "8.8.8.8", "172.32.0.1", "2001:db8::1")
            .forEach { assertFalse(isPrivateNetworkLiteral(it), it) }
    }

    @Test
    fun `a value that is not an address literal is never resolved or treated as private`() {
        assertFalse(isPrivateNetworkLiteral("localhost"))
        assertFalse(isPrivateNetworkLiteral("internal.example"))
    }
}
