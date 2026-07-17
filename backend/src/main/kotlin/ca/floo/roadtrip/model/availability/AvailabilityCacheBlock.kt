package ca.floo.roadtrip.model.availability

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityCacheBlock(
    val hit: Boolean,
    @SerialName("age_seconds") val ageSeconds: Long,
    @SerialName("ttl_seconds") val ttlSeconds: Long,
)
