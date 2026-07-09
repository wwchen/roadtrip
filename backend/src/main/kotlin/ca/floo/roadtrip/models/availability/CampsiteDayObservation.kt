package ca.floo.roadtrip.models.availability

import java.time.Instant
import java.time.LocalDate

data class CampsiteDayObservation(
    val campsiteId: Long?,
    val date: LocalDate,
    val observedAt: Instant,
    val status: AvailabilityStatus,
)
