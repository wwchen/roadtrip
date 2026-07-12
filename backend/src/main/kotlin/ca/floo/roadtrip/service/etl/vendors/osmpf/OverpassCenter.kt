package ca.floo.roadtrip.service.etl.vendors.osmpf

import kotlinx.serialization.Serializable

@Serializable
data class OverpassCenter(
    val lat: Double,
    val lon: Double,
)
