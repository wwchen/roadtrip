package ca.floo.roadtrip.client.reserveamerica

import ca.floo.roadtrip.model.availability.AvailabilityStatus
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
