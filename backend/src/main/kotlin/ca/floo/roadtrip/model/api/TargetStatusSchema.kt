package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TargetStatusSchema(
    val target: String,
    @SerialName("last_run") val lastRun: Long? = null,
    val kind: String? = null,
    val status: String? = null,
    @SerialName("age_sec") val ageSec: Long? = null,
)
