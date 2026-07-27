package ca.floo.roadtrip.service.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val ALGORITHM = "AES"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val NONCE_BYTES = 12
private const val TAG_BITS = 128

/**
 * AES-256-GCM sealing for secrets at rest. Output layout is nonce||ciphertext+tag,
 * so [open] is self-describing. The key comes from config (see [ca.floo.roadtrip.config.SecretsConfig]);
 * a leaked database row is useless without it.
 */
class SecretCipher(
    key: ByteArray,
) {
    init {
        require(key.size == 32) { "encryption key must be 32 bytes (AES-256)" }
    }

    private val keySpec = SecretKeySpec(key, ALGORITHM)
    private val random = SecureRandom()

    fun seal(plaintext: String): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
            }
        return nonce + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    fun open(ciphertext: ByteArray): String {
        val nonce = ciphertext.copyOfRange(0, NONCE_BYTES)
        val body = ciphertext.copyOfRange(NONCE_BYTES, ciphertext.size)
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
            }
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }
}
