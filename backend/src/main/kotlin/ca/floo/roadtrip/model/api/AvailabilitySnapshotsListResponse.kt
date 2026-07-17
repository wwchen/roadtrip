package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class AvailabilitySnapshotsListResponse(
    val snapshots: List<AvailabilitySnapshotSchema>,
)
