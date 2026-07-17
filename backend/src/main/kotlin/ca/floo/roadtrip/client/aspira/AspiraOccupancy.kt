package ca.floo.roadtrip.client.aspira

import kotlinx.serialization.Serializable

@Serializable
data class AspiraOccupancy(
    val resourceLocationId: Int,
    val resourceOccupancy: List<AspiraResourceOccupancy> = emptyList(),
)
