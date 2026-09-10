package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import kotlinx.serialization.Serializable

/**
 * One date of a campground week. [status] is the rollup over [cells] and
 * [watchable] is true when any cell is, both decided here so every client
 * renders the same day.
 */
@Serializable
data class AvailabilityDayDto(
    val date: String,
    val status: AvailabilityStatus,
    val watchable: Boolean,
    /** Campsite id → cell, in ascending id order. */
    val cells: Map<Long, AvailabilityCellDto>,
)
