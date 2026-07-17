package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class TargetStatusSchema(
    val target: String,
    val last_run: Long? = null,
    val kind: String? = null,
    val status: String? = null,
    val age_sec: Long? = null,
)
