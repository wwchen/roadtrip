package ca.floo.roadtrip.clients.reservecalifornia

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import java.time.Instant
import java.time.LocalDate

data class ReserveCaliforniaGridAvailability(
    val facilityId: Long,
    val observedAt: Instant,
    val statuses: Map<String, Map<LocalDate, AvailabilityStatus>>,
    val unitNames: Map<String, String?> = emptyMap(),
)
