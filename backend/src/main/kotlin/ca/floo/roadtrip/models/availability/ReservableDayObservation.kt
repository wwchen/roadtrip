package ca.floo.roadtrip.models.availability

import java.time.Instant
import java.time.LocalDate

data class ReservableDayObservation(
    val reservableId: String,
    val date: LocalDate,
    val observedAt: Instant,
    val status: AvailabilityStatus,
)
