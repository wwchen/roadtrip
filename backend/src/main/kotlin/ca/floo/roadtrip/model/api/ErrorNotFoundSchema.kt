package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class ErrorNotFoundSchema(
    val error: String,
    val id: Long? = null,
)
