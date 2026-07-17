package ca.floo.roadtrip.model.domain

import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * One row in the `tesla_superchargers` table.
 */
data class TeslaSupercharger(
    val id: Long,
    val locationSlug: String,
    val locationGuid: String?,
    val commonSiteName: String,
    val siteStatus: String,
    val accessType: String?,
    val openToPublic: Boolean,
    val openToNonTeslas: Boolean?,
    val trailerFriendly: Boolean?,
    val twentyFourSeven: Boolean?,
    val stallCount: Int?,
    val maxPowerKw: Int?,
    val address: JsonElement,
    val region: String?,
    val country: String?,
    val timeZone: String?,
    val amenities: JsonElement,
    val hardwareCounts: JsonElement,
    val pricebooks: JsonElement,
    val availabilityProfile: JsonElement,
    val infoUrl: String?,
    val indexPayload: JsonElement,
    val detailPayload: JsonElement,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)
