package ca.floo.roadtrip.model.availability.reservecalifornia

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import java.time.Instant
import java.time.LocalDate

data class ReserveCaliforniaGridAvailability(
    val facilityId: Long,
    val observedAt: Instant,
    val statuses: Map<String, Map<LocalDate, AvailabilityStatus>>,
    val unitNames: Map<String, String?> = emptyMap(),
)
