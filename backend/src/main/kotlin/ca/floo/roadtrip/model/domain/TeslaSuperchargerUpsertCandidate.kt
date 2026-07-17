package ca.floo.roadtrip.model.domain

import kotlinx.serialization.json.JsonElement

const val DEFAULT_TESLA_SITE_STATUS = "open"

data class TeslaSuperchargerUpsertCandidate(
    val locationSlug: String,
    val commonSiteName: String,
    val latitude: Double,
    val longitude: Double,
    val locationGuid: String? = null,
    val siteStatus: String = DEFAULT_TESLA_SITE_STATUS,
    val accessType: String? = null,
    val openToPublic: Boolean = true,
    val openToNonTeslas: Boolean? = null,
    val trailerFriendly: Boolean? = null,
    val twentyFourSeven: Boolean? = null,
    val stallCount: Int? = null,
    val maxPowerKw: Int? = null,
    val address: JsonElement? = null,
    val region: String? = null,
    val country: String? = null,
    val timeZone: String? = null,
    val amenities: JsonElement? = null,
    val hardwareCounts: JsonElement? = null,
    val pricebooks: JsonElement? = null,
    val availabilityProfile: JsonElement? = null,
    val infoUrl: String? = null,
    val indexPayload: JsonElement? = null,
    val detailPayload: JsonElement? = null,
)
