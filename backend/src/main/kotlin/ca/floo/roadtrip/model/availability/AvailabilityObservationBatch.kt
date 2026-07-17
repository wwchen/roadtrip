package ca.floo.roadtrip.model.availability

import java.time.LocalDate

data class AvailabilityObservationBatch(
    val provider: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val observations: List<CampsiteDayObservation>,
    val cacheBlock: AvailabilityCacheBlock,
    val seasonBlock: AvailabilitySeasonBlock? = null,
    val campgroundId: String? = null,
    val host: String? = null,
    val mapId: String? = null,
    val campsiteId: Long? = null,
)
