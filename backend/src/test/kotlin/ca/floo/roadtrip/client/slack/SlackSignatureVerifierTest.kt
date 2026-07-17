package ca.floo.roadtrip.client.slack

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals

class SlackSignatureVerifierTest {
    private val secret = "8f742231b10e8888abcd99yyyzzz85a5"
    private val now = Instant.parse("2026-07-06T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    // Signs a body the way Slack would, so a "valid" request the test builds
    // matches what the verifier expects to see over the wire.
    private fun sign(
        ts: Long,
        body: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val hex = mac.doFinal("v0:$ts:$body".toByteArray()).joinToString("") { "%02x".format(it) }
        return "v0=$hex"
    }

    private fun verifier() = SlackSignatureVerifier(secret, clock)

    @Test
    fun `verified when signature matches and timestamp is fresh`() {
        val ts = now.epochSecond
        val body = "payload=%7B%22ok%22%3Atrue%7D"
        val result = verifier().verify(ts.toString(), sign(ts, body), body.toByteArray())
        assertEquals(SlackSignatureVerifier.Result.Verified, result)
    }

    @Test
    fun `rejected when body was tampered with in flight`() {
        val ts = now.epochSecond
        val signed = sign(ts, "original")
        val result = verifier().verify(ts.toString(), signed, "tampered".toByteArray())
        assertEquals(SlackSignatureVerifier.Result.Rejected, result)
    }

    @Test
    fun `rejected when signature is for a different secret`() {
        val ts = now.epochSecond
        val body = "payload=x"
        // Sign with a DIFFERENT secret than the verifier holds — mimics an
        // attacker guessing/forging a signature without the real secret.
        val macAttacker = Mac.getInstance("HmacSHA256")
        macAttacker.init(SecretKeySpec("wrong-secret".toByteArray(), "HmacSHA256"))
        val hex = macAttacker.doFinal("v0:$ts:$body".toByteArray()).joinToString("") { "%02x".format(it) }
        val result = verifier().verify(ts.toString(), "v0=$hex", body.toByteArray())
        assertEquals(SlackSignatureVerifier.Result.Rejected, result)
    }

    @Test
    fun `rejected when timestamp is more than 5 minutes in the past`() {
        val ts = now.minus(Duration.ofMinutes(6)).epochSecond
        val body = "payload=x"
        val result = verifier().verify(ts.toString(), sign(ts, body), body.toByteArray())
        assertEquals(SlackSignatureVerifier.Result.Rejected, result)
    }

    @Test
    fun `rejected when timestamp is more than 5 minutes in the future (clock drift bound)`() {
        val ts = now.plus(Duration.ofMinutes(6)).epochSecond
        val body = "payload=x"
        val result = verifier().verify(ts.toString(), sign(ts, body), body.toByteArray())
        assertEquals(SlackSignatureVerifier.Result.Rejected, result)
    }

    @Test
    fun `rejected when the timestamp header is missing or non-numeric`() {
        val body = "payload=x"
        assertEquals(SlackSignatureVerifier.Result.Rejected, verifier().verify(null, sign(now.epochSecond, body), body.toByteArray()))
        assertEquals(
            SlackSignatureVerifier.Result.Rejected,
            verifier().verify("not-a-number", sign(now.epochSecond, body), body.toByteArray()),
        )
    }

    @Test
    fun `rejected when the signature header is missing or does not start with v0=`() {
        val ts = now.epochSecond
        val body = "payload=x"
        assertEquals(SlackSignatureVerifier.Result.Rejected, verifier().verify(ts.toString(), null, body.toByteArray()))
        // A future v1= scheme is opaque to a v0 verifier — reject rather than accept.
        assertEquals(SlackSignatureVerifier.Result.Rejected, verifier().verify(ts.toString(), "v1=deadbeef", body.toByteArray()))
    }
}
