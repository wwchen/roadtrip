package ca.floo.roadtrip.model.routing

import kotlinx.serialization.Serializable

/** Per-leg summary: one entry per segment between adjacent waypoints. */
@Serializable
data class RouteLeg(
    val distanceMeters: Double,
    val durationSeconds: Double,
)
