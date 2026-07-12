package ca.floo.roadtrip.service.etl.vendors.tesla

import kotlinx.serialization.Serializable

@Serializable
data class TeslaIndexRow(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val title: String? = null,
    @kotlinx.serialization.SerialName("location_type") val locationType: List<String>? = null,
    @kotlinx.serialization.SerialName("location_url_slug") val locationUrlSlug: String? = null,
    @kotlinx.serialization.SerialName("supercharger_function") val superchargerFunction: TeslaSuperchargerFunction? = null,
)
