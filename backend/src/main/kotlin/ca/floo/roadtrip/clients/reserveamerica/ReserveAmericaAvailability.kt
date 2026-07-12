package ca.floo.roadtrip.clients.reserveamerica

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import java.time.Instant
import java.time.LocalDate

data class ReserveAmericaAvailability(
    val contractCode: String,
    val parkId: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val observedAt: Instant,
    val statuses: Map<String, Map<LocalDate, AvailabilityStatus>>,
)
