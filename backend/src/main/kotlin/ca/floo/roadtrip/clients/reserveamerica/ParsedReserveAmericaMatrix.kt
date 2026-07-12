package ca.floo.roadtrip.clients.reserveamerica

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import java.time.LocalDate

data class ParsedReserveAmericaMatrix(
    val statuses: Map<String, Map<LocalDate, AvailabilityStatus>>,
    val totalSites: Int?,
)
