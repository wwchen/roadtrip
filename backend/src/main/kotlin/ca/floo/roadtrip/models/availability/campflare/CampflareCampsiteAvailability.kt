package ca.floo.roadtrip.models.availability.campflare

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import java.time.LocalDate

data class CampflareCampsiteAvailability(
    val campsiteId: String,
    val availability: Map<LocalDate, AvailabilityStatus>,
)
