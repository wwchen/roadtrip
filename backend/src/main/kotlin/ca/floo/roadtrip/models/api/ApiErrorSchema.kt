package ca.floo.roadtrip.models.api

import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorSchema(
    val error: String,
    val detail: String? = null,
)
