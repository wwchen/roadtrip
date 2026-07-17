package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
internal data class RouteErrorDto(
    val error: String,
    val detail: String,
)
