package ca.floo.roadtrip.model.availability

import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import java.time.LocalDate

data class AvailabilityObservationBatch(
    val provider: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val observations: List<CampsiteDayObservation>,
    val cacheBlock: AvailabilityCacheBlock,
    val seasonBlock: AvailabilitySeasonBlock? = null,
    val scope: BookingProviderRef? = null,
    val campsiteId: Long? = null,
)
