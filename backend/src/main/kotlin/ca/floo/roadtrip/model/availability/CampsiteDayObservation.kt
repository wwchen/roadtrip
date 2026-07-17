package ca.floo.roadtrip.model.availability

import java.time.Instant
import java.time.LocalDate

data class CampsiteDayObservation(
    val campsiteId: Long?,
    val date: LocalDate,
    val observedAt: Instant,
    val status: AvailabilityStatus,
)
