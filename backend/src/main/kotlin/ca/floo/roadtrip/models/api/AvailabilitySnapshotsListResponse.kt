package ca.floo.roadtrip.models.api

import kotlinx.serialization.Serializable

@Serializable
data class AvailabilitySnapshotsListResponse(
    val snapshots: List<AvailabilitySnapshotSchema>,
)
