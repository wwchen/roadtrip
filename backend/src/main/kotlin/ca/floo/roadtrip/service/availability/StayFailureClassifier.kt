package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.StayFailureReason
import ca.floo.roadtrip.model.api.StayOutcome

/**
 * A campground's failure, classified: the [outcome] it amounts to, the [reason]
 * behind it, and whether asking again can help.
 *
 * [retryable] is null where it carries no information: a [StayOutcome.REFUSED]
 * is never worth retrying and a [StayOutcome.TIMED_OUT] always is, so only
 * [StayOutcome.FAILED] answers it. [reason] is likewise null for a timeout, which
 * has none, so no placeholder reason exists that a caller could emit by mistake.
 */
internal data class StayFailure(
    val outcome: StayOutcome,
    val reason: StayFailureReason?,
    val retryable: Boolean?,
)

/**
 * The wire codes the classifier reads, named once. They are the strings
 * [ca.floo.roadtrip.model.availability.AvailabilityProviderError] and
 * [AvailabilityServiceError] already carry. The classifier test pins the codes
 * it lists, by hand: a new subclass of either hierarchy has to be added there, and
 * to [stayFailureOf], or it classifies as unknown.
 */
internal object StayFailureCode {
    const val START_BEFORE_EARLIEST = "start_before_earliest"
    const val BEYOND_BOOKING_HORIZON = "beyond_booking_horizon"
    const val WINDOW_TOO_LONG = "window_too_long"
    const val RATE_LIMITED = "rate_limited"
    const val UPSTREAM_BLOCKED = "upstream_blocked"
    const val UPSTREAM_5XX = "upstream_5xx"
    const val UPSTREAM_UNREACHABLE = "upstream_unreachable"
    const val PROVIDER_MISCONFIGURED = "provider_misconfigured"
    const val UNSUPPORTED = "unsupported"
    const val NOT_FOUND = "not_found"
    const val UNKNOWN_CAMPGROUND = "unknown_campground"
}

private fun refused(reason: StayFailureReason) = StayFailure(StayOutcome.REFUSED, reason, retryable = null)

private fun retryable(reason: StayFailureReason) = StayFailure(StayOutcome.FAILED, reason, retryable = true)

private fun terminal(reason: StayFailureReason) = StayFailure(StayOutcome.FAILED, reason, retryable = false)

private val timedOutFailure = StayFailure(StayOutcome.TIMED_OUT, reason = null, retryable = null)

/**
 * Classifies the wire code of a per-campground failure — an
 * [ca.floo.roadtrip.model.availability.AvailabilityProviderError.code], an
 * [AvailabilityServiceError.error], or [STAY_TIMEOUT_ERROR] — so the server, not
 * the browser, says whether a failure means "these dates are refused here" or
 * "we failed to ask".
 *
 * `end_before_start` and `bad_date_window` fall to the UNKNOWN branch on purpose:
 * the route refuses them for the whole request before any fan-out, so they cannot
 * reach a per-campground answer. So does any code this function has never heard
 * of; UNKNOWN is not retryable because nothing says asking again would help.
 * `not_found` (no campground row is linked to the POI id) and `unknown_campground`
 * (the campground resolved but no provider claims it) stay separate reasons: the
 * UI renders both the same way, but collapsing them would tell a caller a POI does
 * not exist when it does.
 */
internal fun stayFailureOf(code: String): StayFailure =
    when (code) {
        StayFailureCode.START_BEFORE_EARLIEST -> refused(StayFailureReason.START_BEFORE_EARLIEST)
        StayFailureCode.BEYOND_BOOKING_HORIZON -> refused(StayFailureReason.BEYOND_BOOKING_HORIZON)
        StayFailureCode.WINDOW_TOO_LONG -> refused(StayFailureReason.WINDOW_TOO_LONG)
        STAY_TIMEOUT_ERROR -> timedOutFailure
        StayFailureCode.RATE_LIMITED -> retryable(StayFailureReason.RATE_LIMITED)
        StayFailureCode.UPSTREAM_BLOCKED -> retryable(StayFailureReason.UPSTREAM_BLOCKED)
        StayFailureCode.UPSTREAM_5XX -> retryable(StayFailureReason.UPSTREAM_5XX)
        StayFailureCode.UPSTREAM_UNREACHABLE -> retryable(StayFailureReason.UPSTREAM_UNREACHABLE)
        StayFailureCode.PROVIDER_MISCONFIGURED -> terminal(StayFailureReason.PROVIDER_MISCONFIGURED)
        StayFailureCode.UNSUPPORTED -> terminal(StayFailureReason.UNSUPPORTED)
        StayFailureCode.NOT_FOUND -> terminal(StayFailureReason.NOT_FOUND)
        StayFailureCode.UNKNOWN_CAMPGROUND -> terminal(StayFailureReason.UNKNOWN_CAMPGROUND)
        else -> terminal(StayFailureReason.UNKNOWN)
    }
