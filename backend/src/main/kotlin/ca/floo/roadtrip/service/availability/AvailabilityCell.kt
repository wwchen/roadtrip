package ca.floo.roadtrip.service.availability

import java.time.LocalDate

/**
 * One `(campsite, date)` cell of availability — the grain the `availability`
 * interval table is keyed at, and the grain the freshness policy decides at.
 */
data class AvailabilityCell(
    val campsiteId: Long,
    val targetDate: LocalDate,
)
