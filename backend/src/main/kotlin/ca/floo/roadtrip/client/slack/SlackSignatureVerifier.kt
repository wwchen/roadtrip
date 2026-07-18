package ca.floo.roadtrip.client.slack

import org.slf4j.LoggerFactory
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
 * The endpoint answers a single 401 for every failure (no oracle), but the
 * verifier logs *why* it rejected at INFO — the header shape and clock drift
 * are what an operator needs to see when Slack's saved Request URL check fails
 * or an ngrok tunnel's URL is talking to the wrong signing secret.
 */
class SlackSignatureVerifier(
    private val signingSecret: String,
    private val clock: Clock = Clock.systemUTC(),
    private val replayWindow: Duration = defaultReplayWindow,
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
        val ts = timestampHeader?.toLongOrNull()
        if (ts == null) {
            log.info(
                "Slack signature verify FAILED: missing/non-numeric X-Slack-Request-Timestamp (present={}, value={})",
                timestampHeader != null,
                timestampHeader?.let { "\"${it.take(32)}\"" } ?: "null",
            )
            return Result.Rejected
        }
        val sig = signatureHeader
        if (sig == null || !sig.startsWith(SIGNATURE_PREFIX)) {
            log.info(
                "Slack signature verify FAILED: missing/malformed X-Slack-Signature (present={}, starts-with-v0={})",
                sig != null,
                sig?.startsWith(SIGNATURE_PREFIX) ?: false,
            )
            return Result.Rejected
        }

        val now = clock.instant()
        val requestAt = Instant.ofEpochSecond(ts)
        val drift = Duration.between(requestAt, now)
        if (drift.abs() > replayWindow) {
            log.info(
                "Slack signature verify FAILED: timestamp outside {}-min replay window (drift={}s, request_ts={}, now={})",
                replayWindow.toMinutes(),
                drift.seconds,
                requestAt,
                now,
            )
            return Result.Rejected
        }

        val expected = SIGNATURE_PREFIX + hmacSha256Hex(signingSecret, "v0:$ts:".toByteArray(Charsets.UTF_8) + body)
        return if (constantTimeEquals(expected, sig)) {
            log.info("Slack signature verify OK: ts={}, drift={}s, body_bytes={}", requestAt, drift.seconds, body.size)
            Result.Verified
        } else {
            log.info(
                "Slack signature verify FAILED: signature mismatch (body_bytes={}, drift={}s, expected_prefix={}, got_prefix={}) " +
                    "— usually means roadtrip.slack.signing-secret doesn't match the app's Basic Info signing secret, " +
                    "or a proxy re-encoded the body.",
                body.size,
                drift.seconds,
                expected.take(8),
                sig.take(8),
            )
            Result.Rejected
        }
    }

    companion object {
        /** Slack's own recommendation — outside this window the request is treated as replayed. */
        val defaultReplayWindow: Duration = Duration.ofMinutes(5)

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
