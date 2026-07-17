package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorSchema(
    val error: String,
    val detail: String? = null,
)
