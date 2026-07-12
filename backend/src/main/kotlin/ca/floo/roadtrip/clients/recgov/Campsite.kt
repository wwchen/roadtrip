package ca.floo.roadtrip.clients.recgov

import kotlinx.serialization.Serializable

@Serializable
data class Campsite(
    val id: String,
    val site: String?,
    val loop: String?,
    val campsiteType: String?,
    val maxNumPeople: Int?,
    val equipmentTypes: List<String>,
    val availabilities: Map<String, String>,
)
