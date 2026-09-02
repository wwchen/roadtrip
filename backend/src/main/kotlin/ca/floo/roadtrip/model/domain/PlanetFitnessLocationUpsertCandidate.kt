package ca.floo.roadtrip.model.domain

import kotlinx.serialization.json.JsonElement

data class PlanetFitnessLocationUpsertCandidate(
    val locationId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: JsonElement? = null,
    val region: String? = null,
    val country: String? = null,
    val phone: String? = null,
    val infoUrl: String? = null,
    val openingHours: String? = null,
    val brand: String? = null,
    val amenities: JsonElement? = null,
    val payload: JsonElement? = null,
)
