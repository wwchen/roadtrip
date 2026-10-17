package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A stay at one campground, reduced to what a result card says: the window is
 * the request's, every night of it from `start_date` up to (not including)
 * `end_date`.
 */
@Serializable
data class StaySummaryDto(
    val status: StayAvailabilityStatus,
    /** Campsites bookable online for every night of the stay. */
    @SerialName("open_sites") val openSites: Int,
    /** Campsites the answer is over: those of the requested type that fit the group. */
    @SerialName("checked_sites") val checkedSites: Int,
    /**
     * When the oldest observation behind the answer was made, ISO-8601; absent
     * exactly when no campsite was checked, so nothing was read to date it by.
     * An instant rather than an age, so it means the same to a browser whose
     * clock and the server's disagree, and a replayed answer does not look new.
     */
    @SerialName("observed_at") val observedAt: String? = null,
    /** True when the answer came from stored observations, without asking the provider. */
    @SerialName("from_cache") val fromCache: Boolean,
)
