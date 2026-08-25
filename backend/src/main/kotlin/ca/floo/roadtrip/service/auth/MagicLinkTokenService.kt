package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import java.security.SecureRandom
import java.util.Base64

private const val TOKEN_BYTES = 32

/**
 * One token per watch, minted on first use and reused after, so a link in an old
 * email keeps working.
 *
 * Minting only: verifying a link is
 * [AvailabilityWatchRepo.findByIdMatchingMagicLinkToken], so the token is never
 * read back out of the database.
 */
class MagicLinkTokenService(
    private val watchRepo: AvailabilityWatchRepo,
) {
    private val secureRandom = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    fun issue(watchId: Long): String? = watchRepo.ensureMagicLinkToken(watchId, randomToken())

    private fun randomToken(): String {
        val buffer = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(buffer)
        return encoder.encodeToString(buffer)
    }
}
