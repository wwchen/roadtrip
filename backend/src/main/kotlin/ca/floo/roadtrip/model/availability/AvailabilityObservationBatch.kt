package ca.floo.roadtrip.model.availability

import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import java.time.LocalDate

/**
 * What a provider answered, as base-unit facts: one observation per
 * `(campsite, night)`, each naming its campsite.
 *
 * It carries no answer-level fact — no season block, no rollup. "Closed for the
 * season, reopening on X" is a statement about the campsites a *request* asked
 * about, derived above this from stored cells, so that it survives a cache hit
 * instead of existing only on the call that happened to fetch.
 */
data class AvailabilityObservationBatch(
    val provider: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val observations: List<CampsiteDayObservation>,
    val cacheBlock: AvailabilityCacheBlock,
    val scope: BookingProviderRef? = null,
    val campsiteId: Long? = null,
)
