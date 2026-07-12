package ca.floo.roadtrip.clients.aspira

import kotlinx.serialization.Serializable

@Serializable
data class AspiraResourceOccupancy(
    val resourceId: Long,
    val filtered: Boolean = false,
    val availability: Int,
)
