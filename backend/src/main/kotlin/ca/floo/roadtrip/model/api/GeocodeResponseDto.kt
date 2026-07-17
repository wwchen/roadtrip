package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
internal data class GeocodeResponseDto(
    val results: List<GeocodeResultDto>,
)
