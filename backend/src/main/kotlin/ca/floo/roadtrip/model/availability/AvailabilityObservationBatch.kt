package ca.floo.roadtrip.model.availability

import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import java.time.LocalDate

data class AvailabilityObservationBatch(
    val provider: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val observations: List<CampsiteDayObservation>,
    val cacheBlock: AvailabilityCacheBlock,
    /**
     * The answer-level season block: when this window is closed, the date it
     * reopens. On the catalog path it is not the adapter's to set — the port's
     * `catalogAvailability` reduces [reopensOnByCampsite] over the campsites the
     * call answers for and replaces whatever is here.
     */
    val seasonBlock: AvailabilitySeasonBlock? = null,
    /**
     * Per campsite, the earliest date the vendor showed it bookable, reaching as
     * far as the fetch did — which can be past [endDate], because a vendor hands
     * back whole months.
     *
     * Per campsite and not one value because "when does this reopen" is a
     * question about the campsites a call answers for, and a batch may cover
     * more than that: a campground's cabins can be shut for the winter while its
     * tents open tomorrow, and the cabin caller must not be given the tents'
     * date. Empty from an adapter that tells campsites apart nowhere, or that
     * has no notion of a season.
     */
    val reopensOnByCampsite: Map<Long, LocalDate> = emptyMap(),
    val scope: BookingProviderRef? = null,
    val campsiteId: Long? = null,
)
