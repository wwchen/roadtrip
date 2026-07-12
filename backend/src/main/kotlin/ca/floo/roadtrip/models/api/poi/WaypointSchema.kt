package ca.floo.roadtrip.models.api.poi

import kotlinx.serialization.Serializable

@Serializable
data class WaypointSchema(
    val lat: Double,
    val lng: Double,
)
