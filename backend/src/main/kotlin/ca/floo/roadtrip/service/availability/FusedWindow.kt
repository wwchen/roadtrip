package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityDayDto
import ca.floo.roadtrip.model.api.AvailabilityWindowState
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilitySeasonBlock

internal data class FusedWindow(
    val state: AvailabilityWindowState,
    val season: AvailabilitySeasonBlock?,
    val cache: AvailabilityCacheBlock?,
    val days: List<AvailabilityDayDto>,
)
