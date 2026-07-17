package ca.floo.roadtrip.model.availability.campflare

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import java.time.LocalDate

data class CampflareCampsiteAvailability(
    val campsiteId: String,
    val availability: Map<LocalDate, AvailabilityStatus>,
)
