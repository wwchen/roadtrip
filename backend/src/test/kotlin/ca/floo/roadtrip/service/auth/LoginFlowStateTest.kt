package ca.floo.roadtrip.service.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

private const val CLIENT_SECRET = "s3cr3t-client-secret"

class LoginFlowStateTest {
    private val key = LoginFlowState.signingKeyFrom(CLIENT_SECRET)

    private val flow =
        LoginFlowState(
            state = "state-abc",
            nonce = "nonce-def",
            codeVerifier = "verifier-ghi",
            returnTo = "/watches?poi=42",
        )

    @Test
    fun `a signed flow round-trips exactly`() {
        val decoded = LoginFlowState.decode(flow.encode(key), key)

        assertEquals(flow, decoded)
    }

    @Test
    fun `a value containing the separator survives`() {
        // return_to legitimately contains dots. Fields are base64url-encoded so
        // the separator can never appear inside one.
        val dotted = flow.copy(returnTo = "/a.b.c?d=e.f")

        assertEquals(dotted, LoginFlowState.decode(dotted.encode(key), key))
    }

    @Test
    fun `a tampered payload is rejected`() {
        val cookie = flow.encode(key)
        val tampered = "X" + cookie.substring(1)

        assertNull(LoginFlowState.decode(tampered, key))
    }

    @Test
    fun `a cookie signed with another key is rejected`() {
        // The attack this exists to stop: flow values planted by someone who
        // does not hold our client secret.
        val foreign = LoginFlowState.signingKeyFrom("a-different-client-secret")

        assertNull(LoginFlowState.decode(flow.encode(foreign), key))
    }

    @Test
    fun `an unsigned payload is rejected`() {
        val payload = flow.encode(key).substringBeforeLast(".")

        assertNull(LoginFlowState.decode(payload, key))
    }

    @Test
    fun `malformed input is rejected rather than throwing`() {
        listOf("", ".", "not-a-cookie", "a.b", "a.b.c.d.e.f.g").forEach {
            assertNull(LoginFlowState.decode(it, key), "should reject '$it'")
        }
    }

    @Test
    fun `the derived key is not the secret and is domain-separated`() {
        val derived = LoginFlowState.signingKeyFrom(CLIENT_SECRET)

        assertNotEquals(CLIENT_SECRET, String(derived, Charsets.UTF_8))
        assertEquals(derived.toList(), LoginFlowState.signingKeyFrom(CLIENT_SECRET).toList())
        assertNotEquals(
            derived.toList(),
            LoginFlowState.signingKeyFrom("$CLIENT_SECRET ").toList(),
        )
    }
}
