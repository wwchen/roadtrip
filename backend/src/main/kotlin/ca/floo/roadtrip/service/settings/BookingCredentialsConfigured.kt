package ca.floo.roadtrip.service.settings

import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.provider.BookingProvider

/**
 * Whether a user has credentials stored for a booking provider.
 *
 * *Configured*, never *proven working*: wrong credentials surface at test time
 * in Settings or at fire time in the failure notification, matching the Slack
 * precedent. A booking adapter reads this to answer `canFulfil`, so `atc` is
 * offered only to users who could plausibly fulfil it.
 *
 * Keyed by provider so the answer belongs to the vendor being asked about
 * rather than to whichever vendor happened to be first.
 */
fun interface BookingCredentialsConfigured {
    fun isConfigured(
        provider: BookingProvider,
        user: UserId,
    ): Boolean
}
