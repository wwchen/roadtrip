package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GeocodeResultDto(
    val id: String,
    @SerialName("place_name") val placeName: String,
    @SerialName("place_type") val placeType: String,
    val lng: Double,
    val lat: Double,
)
