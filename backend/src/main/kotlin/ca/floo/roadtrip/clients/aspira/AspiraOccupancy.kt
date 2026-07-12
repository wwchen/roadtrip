package ca.floo.roadtrip.clients.aspira

import kotlinx.serialization.Serializable

@Serializable
data class AspiraOccupancy(
    val resourceLocationId: Int,
    val resourceOccupancy: List<AspiraResourceOccupancy> = emptyList(),
)
