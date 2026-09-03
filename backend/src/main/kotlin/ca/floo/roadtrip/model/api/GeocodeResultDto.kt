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
    /**
     * `[west, south, east, north]`, or absent when the feature has no extent.
     *
     * Four bare numbers rather than a named object because that is already the
     * bbox shape on this API (`PoisRequestSchema`), and a second encoding of the
     * same value is one more thing for a client to get backwards.
     */
    val bbox: List<Double>? = null,
)
