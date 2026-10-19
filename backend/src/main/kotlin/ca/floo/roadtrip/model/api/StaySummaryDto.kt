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
     * when no observation backs the answer, so there is nothing to date it by.
     * That happens two ways, which read differently: no campsite matched the
     * filters (`checked_sites` is 0), or campsites matched but the provider
     * returned nothing for the window (`checked_sites` is above 0, and the
     * status is `no_data`). An instant rather than an age, so it means the same
     * to a browser whose clock and the server's disagree, and a replayed answer
     * does not look new.
     */
    @SerialName("observed_at") val observedAt: String? = null,
    /**
     * True when the answer came from stored observations, without asking the
     * provider. False can also mean nothing was served: with no campsite checked
     * there is no answer to have come from anywhere, and this field is required,
     * so a browser cannot tell that from a genuine miss.
     */
    @SerialName("from_cache") val fromCache: Boolean,
)
