package ca.floo.roadtrip.config

import java.util.Base64

private const val ENCRYPTION_KEY = "encryption-key"

/**
 * Symmetric key for [ca.floo.roadtrip.service.security.SecretCipher], base64 of
 * 32 bytes. [fromConfig] returns null when absent/blank — a first-class
 * "secret storage disabled" state (settings that need it answer 503), mirroring
 * [AuthConfig.fromConfig] and [SlackConfig.fromConfig].
 */
data class SecretsConfig(
    val encryptionKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is SecretsConfig && encryptionKey.contentEquals(other.encryptionKey)

    override fun hashCode(): Int = encryptionKey.contentHashCode()

    companion object {
        fun fromConfig(config: ConfigSection): SecretsConfig? {
            val raw = config.value(ENCRYPTION_KEY) ?: return null
            val decoded = Base64.getDecoder().decode(raw)
            require(decoded.size == 32) { "$ENCRYPTION_KEY must be base64 of 32 bytes" }
            return SecretsConfig(decoded)
        }
    }
}
