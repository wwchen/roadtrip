package ca.floo.roadtrip.service.etl.vendors.tesla

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TeslaLocationDetail(
    val name: String? = null,
    @kotlinx.serialization.SerialName("locationGUID") val locationGuid: String? = null,
    val address: TeslaAddress? = null,
    val timeZone: String? = null,
    val openToPublic: Boolean? = null,
    val publicStallCount: Int? = null,
    val maxPowerKw: Int? = null,
    val accessType: String? = null,
    val openToNonTeslas: Boolean? = null,
    val isTrailerFriendly: Boolean? = null,
    val accessHours: TeslaAccessHours? = null,
    // Pricebook entries Tesla returns alongside the location detail. Held
    // as raw JsonElements; the FE knows the shape and renders only the
    // entries it cares about (Tesla CHARGING, first CONGESTION row).
    val effectivePricebooks: List<JsonElement> = emptyList(),
)
