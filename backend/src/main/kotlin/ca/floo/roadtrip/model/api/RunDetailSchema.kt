package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RunDetailSchema(
    val id: Long,
    val target: String,
    val kind: String,
    val status: String,
    @SerialName("triggered_by") val triggeredBy: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
    val notes: String? = null,
    val phases: List<IngestRunPhaseSchema>,
)
