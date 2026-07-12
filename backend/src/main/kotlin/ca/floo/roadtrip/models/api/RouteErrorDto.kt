package ca.floo.roadtrip.models.api

import kotlinx.serialization.Serializable

@Serializable
internal data class RouteErrorDto(
    val error: String,
    val detail: String,
)
