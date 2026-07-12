package ca.floo.roadtrip.models.metadata

import kotlinx.serialization.Serializable

@Serializable
data class RequestMeta(
    val url: String,
    val method: String,
    val headers: Map<String, String> = emptyMap(),
)
