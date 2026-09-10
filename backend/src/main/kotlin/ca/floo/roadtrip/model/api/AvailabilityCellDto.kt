package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * One campsite's state on one date. [watchable] is the backend's answer to
 * "could a watch be created on this cell" — the status, the provider's polling
 * support and the booking window all folded in, so no client re-decides it.
 */
@Serializable
data class AvailabilityCellDto(
    val status: AvailabilityStatus,
    val watchable: Boolean,
) {
    companion object {
        /**
         * The one watchability predicate, so the fused campground week and the
         * per-campsite bulk envelope can never answer it differently: a status a
         * watch could fire on, a provider the poller can reach, and a date the
         * vendor will still quote.
         */
        fun of(
            status: AvailabilityStatus,
            pollingSupported: Boolean,
            date: LocalDate,
            earliestDate: LocalDate,
        ): AvailabilityCellDto =
            AvailabilityCellDto(
                status = status,
                watchable = status.watchable && pollingSupported && !date.isBefore(earliestDate),
            )
    }
}
