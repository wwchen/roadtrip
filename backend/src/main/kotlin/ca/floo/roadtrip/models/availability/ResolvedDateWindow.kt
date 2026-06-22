package ca.floo.roadtrip.models.availability

import java.time.LocalDate

internal data class ResolvedDateWindow(
    val startDate: LocalDate,
    val endDate: LocalDate,
)
