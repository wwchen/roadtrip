package ca.floo.roadtrip.models.availability

import java.time.LocalDate
import java.time.ZoneId

internal data class PoiDateContext(
    val timeZone: ZoneId,
    val earliestDate: LocalDate,
)
