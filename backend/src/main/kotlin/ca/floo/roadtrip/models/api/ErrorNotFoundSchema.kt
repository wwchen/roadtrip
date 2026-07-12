package ca.floo.roadtrip.models.api

import kotlinx.serialization.Serializable

@Serializable
data class ErrorNotFoundSchema(
    val error: String,
    val id: Long? = null,
)
