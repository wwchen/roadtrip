package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why a campground's stay check did not produce a summary, for an outcome of
 * [StayOutcome.REFUSED] or [StayOutcome.FAILED].
 *
 * The first three are refusals: the dates cannot work at this campground.
 * Everything else is a failure to ask. `end_before_start` and `bad_date_window`
 * are deliberately not members: the route refuses an inverted or malformed window
 * for the whole request before any fan-out, so neither can reach a per-campground
 * answer. [NOT_FOUND] is a failure, not a refusal, so an unknown POI id never
 * renders as "these dates do not work here".
 */
@Serializable
enum class StayFailureReason {
    @SerialName("start_before_earliest")
    START_BEFORE_EARLIEST,

    @SerialName("beyond_booking_horizon")
    BEYOND_BOOKING_HORIZON,

    @SerialName("window_too_long")
    WINDOW_TOO_LONG,

    @SerialName("rate_limited")
    RATE_LIMITED,

    @SerialName("upstream_blocked")
    UPSTREAM_BLOCKED,

    @SerialName("upstream_5xx")
    UPSTREAM_5XX,

    @SerialName("upstream_unreachable")
    UPSTREAM_UNREACHABLE,

    @SerialName("provider_misconfigured")
    PROVIDER_MISCONFIGURED,

    @SerialName("unsupported")
    UNSUPPORTED,

    /** No campground row is linked to the POI id, so the POI itself is not one we can check. */
    @SerialName("not_found")
    NOT_FOUND,

    /**
     * The campground resolved, but no provider claims it. Kept apart from
     * [NOT_FOUND] so a caller is never told a POI does not exist when it does;
     * the UI renders both the same way, but the distinction is worth keeping in logs.
     */
    @SerialName("unknown_campground")
    UNKNOWN_CAMPGROUND,

    @SerialName("unknown")
    UNKNOWN,
}
