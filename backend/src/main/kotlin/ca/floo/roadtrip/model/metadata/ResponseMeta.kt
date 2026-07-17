package ca.floo.roadtrip.model.metadata

import kotlinx.serialization.Serializable

@Serializable
data class ResponseMeta(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
)
