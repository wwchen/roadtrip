package ca.floo.roadtrip.service.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HMAC_SHA256 = "HmacSHA256"
private const val FIELD_SEPARATOR = "."
private const val FIELD_COUNT = 4
private const val KEY_DERIVATION_LABEL = "roadtrip-login-flow-cookie-v1"

/**
 * The per-attempt secrets that must survive the redirect to the provider and
 * come back unaltered.
 *
 * Carried in a short-lived signed cookie rather than a table: these expire in
 * minutes, so a table would need a sweep job for no benefit.
 *
 * [encode] signs the payload and [decode] refuses anything whose signature does
 * not verify. The signature matters because the cookie is the only thing binding
 * a callback to the browser that started the flow — an attacker able to plant
 * flow values in a victim's browser could otherwise complete a login *as
 * themselves* in the victim's session. `HttpOnly` already blocks script access;
 * the signature additionally blocks a cookie planted by a sibling host.
 */
internal data class LoginFlowState(
    val state: String,
    val nonce: String,
    val codeVerifier: String,
    val returnTo: String,
) {
    companion object {
        /**
         * Verifies and parses. Returns null for anything malformed or unsigned —
         * callers treat that identically to "no flow in progress", so a tampered
         * cookie is indistinguishable from a missing one.
         */
        fun decode(
            cookie: String,
            signingKey: ByteArray,
        ): LoginFlowState? {
            val lastSeparator = cookie.lastIndexOf(FIELD_SEPARATOR)
            if (lastSeparator <= 0) return null

            val payload = cookie.substring(0, lastSeparator)
            val signature = cookie.substring(lastSeparator + 1)
            if (!constantTimeEquals(sign(payload, signingKey), signature)) return null

            val fields = payload.split(FIELD_SEPARATOR)
            if (fields.size != FIELD_COUNT) return null
            val decoded = fields.map { decodeField(it) ?: return null }

            return LoginFlowState(
                state = decoded[0],
                nonce = decoded[1],
                codeVerifier = decoded[2],
                returnTo = decoded[3],
            )
        }

        /**
         * Derives the cookie signing key from the OIDC client secret.
         *
         * Domain-separated by [KEY_DERIVATION_LABEL] so this key cannot be
         * confused with the secret itself, and so a future second use derives a
         * different key from the same input. Deriving rather than configuring
         * avoids a second secret to provision and rotate.
         */
        fun signingKeyFrom(clientSecret: String): ByteArray {
            require(clientSecret.isNotBlank()) { "a client secret is required to derive the login-flow cookie key" }
            return hmac(clientSecret.toByteArray(StandardCharsets.UTF_8), KEY_DERIVATION_LABEL)
        }

        internal fun sign(
            payload: String,
            signingKey: ByteArray,
        ): String = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(signingKey, payload))

        private fun hmac(
            key: ByteArray,
            message: String,
        ): ByteArray =
            Mac.getInstance(HMAC_SHA256).run {
                init(SecretKeySpec(key, HMAC_SHA256))
                doFinal(message.toByteArray(StandardCharsets.UTF_8))
            }

        internal fun encodeField(value: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

        private fun decodeField(value: String): String? =
            runCatching { String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8) }.getOrNull()

        /** Signature comparison must not leak position of first difference. */
        private fun constantTimeEquals(
            a: String,
            b: String,
        ): Boolean =
            MessageDigest.isEqual(
                a.toByteArray(StandardCharsets.UTF_8),
                b.toByteArray(StandardCharsets.UTF_8),
            )
    }
}

/**
 * Signs and serializes as `state.nonce.verifier.returnTo.signature`. Fields are
 * base64url-encoded so the separator can never appear inside one.
 *
 * An extension rather than a member so [LoginFlowState] stays a pure data
 * carrier.
 */
internal fun LoginFlowState.encode(signingKey: ByteArray): String {
    val payload =
        listOf(state, nonce, codeVerifier, returnTo)
            .joinToString(".") { LoginFlowState.encodeField(it) }
    return "$payload.${LoginFlowState.sign(payload, signingKey)}"
}
