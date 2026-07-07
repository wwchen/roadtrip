package ca.floo.roadtrip.clients.slack

import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies inbound Slack interactivity requests per Slack's request-signing
 * scheme (https://api.slack.com/authentication/verifying-requests-from-slack):
 * HMAC-SHA256 over `v0:{timestamp}:{body}` with the app's signing secret,
 * compared in constant time to the `X-Slack-Signature` header.
 *
 * Rejects requests older than [replayWindow] (default: 5 min, Slack's own
 * guidance) so a stolen signature can't be replayed indefinitely, and rejects
 * missing/malformed headers as unverifiable. The bot token stays outbound-only:
 * this verifier is the only trust boundary for inbound interactivity.
 *
 * All rejections collapse to a single [Result.Rejected] outcome; callers don't
 * differentiate stale-vs-bad-signature (both are "not Slack" as far as the
 * endpoint is concerned) and the timing-attack surface stays flat.
 */
class SlackSignatureVerifier(
    private val signingSecret: String,
    private val clock: Clock = Clock.systemUTC(),
    private val replayWindow: Duration = DEFAULT_REPLAY_WINDOW,
) {
    sealed interface Result {
        data object Verified : Result

        data object Rejected : Result
    }

    /**
     * [timestampHeader] is the raw `X-Slack-Request-Timestamp` value (unix
     * seconds), [signatureHeader] the raw `X-Slack-Signature` (`v0=<hex>`), and
     * [body] the exact request body bytes as received — Slack signs the
     * on-the-wire body, so any re-encoding here would break the compare.
     */
    fun verify(
        timestampHeader: String?,
        signatureHeader: String?,
        body: ByteArray,
    ): Result {
        val ts = timestampHeader?.toLongOrNull() ?: return Result.Rejected
        val sig = signatureHeader?.takeIf { it.startsWith(SIGNATURE_PREFIX) } ?: return Result.Rejected

        val now = clock.instant()
        val requestAt = Instant.ofEpochSecond(ts)
        if (Duration.between(requestAt, now).abs() > replayWindow) return Result.Rejected

        val expected = SIGNATURE_PREFIX + hmacSha256Hex(signingSecret, "v0:$ts:".toByteArray(Charsets.UTF_8) + body)
        return if (constantTimeEquals(expected, sig)) Result.Verified else Result.Rejected
    }

    companion object {
        /** Slack's own recommendation — outside this window the request is treated as replayed. */
        val DEFAULT_REPLAY_WINDOW: Duration = Duration.ofMinutes(5)

        private const val SIGNATURE_PREFIX = "v0="

        private fun hmacSha256Hex(
            secret: String,
            data: ByteArray,
        ): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(data).joinToString("") { "%02x".format(it) }
        }

        /** Same-length XOR compare so a mismatch on the first byte doesn't leak
         *  the length or position of the divergence via elapsed time. Length
         *  mismatches always return false. */
        private fun constantTimeEquals(
            a: String,
            b: String,
        ): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}
