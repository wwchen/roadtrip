package ca.floo.roadtrip.model.api.poi

import kotlinx.serialization.Serializable

@Serializable
data class WaypointSchema(
    val lat: Double,
    val lng: Double,
)
