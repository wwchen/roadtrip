package ca.floo.roadtrip.service.etl.vendors.osmpf

import kotlinx.serialization.Serializable

@Serializable
data class OverpassElement(
    val type: String, // "node" | "way" | "relation"
    val id: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val center: OverpassCenter? = null,
    val tags: Map<String, String>? = null,
)
