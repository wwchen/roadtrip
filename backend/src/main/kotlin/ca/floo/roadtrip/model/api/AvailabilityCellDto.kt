package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import kotlinx.serialization.Serializable

/**
 * One campsite's state on one date. [watchable] is the backend's answer to
 * "could a watch be created on this cell" — the status, the provider's polling
 * support and the booking window all folded in, so no client re-decides it.
 */
@Serializable
data class AvailabilityCellDto(
    val status: AvailabilityStatus,
    val watchable: Boolean,
)
