package ca.floo.roadtrip.client.reserveamerica

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import java.time.LocalDate

data class ParsedReserveAmericaMatrix(
    val statuses: Map<String, Map<LocalDate, AvailabilityStatus>>,
    val totalSites: Int?,
)
