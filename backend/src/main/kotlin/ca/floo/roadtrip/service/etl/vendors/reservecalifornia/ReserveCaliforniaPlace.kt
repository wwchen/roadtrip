package ca.floo.roadtrip.service.etl.vendors.reservecalifornia

import kotlinx.serialization.json.JsonElement

data class ReserveCaliforniaPlace(
    val placeId: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val facilityIds: List<Long>,
    val unitTypeByFacilityId: Map<Long, String>,
    val imageUrl: String?,
    val description: String?,
    val amenities: List<String>,
    val activities: List<String>,
    val raw: JsonElement,
)
