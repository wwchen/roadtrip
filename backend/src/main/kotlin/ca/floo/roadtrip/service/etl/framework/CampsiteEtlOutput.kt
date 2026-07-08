package ca.floo.roadtrip.service.etl.framework

import kotlinx.serialization.json.JsonElement

/**
 * Canonical output for vendor campsite catalog ETLs.
 *
 * Vendor adapters emit these DTOs instead of the retired wide `pois` and
 * `reservables` contracts. The orchestrator persists terminal outputs through
 * typed catalog tables and lean POI wrappers.
 */
data class CampsiteEtlOutput(
    val campsites: List<CampsiteEtlRecord>,
)

data class CampgroundEtlOutput(
    val campgrounds: List<CampgroundEtlRecord>,
)

data class TeslaSuperchargerEtlOutput(
    val superchargers: List<TeslaSuperchargerEtlRecord>,
)

data class PlanetFitnessLocationEtlOutput(
    val locations: List<PlanetFitnessLocationEtlRecord>,
)

data class CampgroundEtlRecord(
    val vendor: String,
    val vendorRefId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val status: String? = null,
    val statusDescription: String? = null,
    val kind: String? = null,
    val shortDescription: String? = null,
    val mediumDescription: String? = null,
    val longDescription: String? = null,
    val location: JsonElement? = null,
    val defaultCampsiteSchedule: JsonElement? = null,
    val amenities: JsonElement? = null,
    val maxRvLength: Double? = null,
    val maxTrailerLength: Double? = null,
    val hasPullThroughSites: Boolean? = null,
    val bigRigFriendly: Boolean? = null,
    val reservationUrl: String? = null,
    val links: JsonElement? = null,
    val photos: JsonElement? = null,
    val alerts: JsonElement? = null,
    val price: JsonElement? = null,
    val cellService: JsonElement? = null,
    val management: JsonElement? = null,
    val contact: JsonElement? = null,
    val connections: JsonElement? = null,
    val metadata: JsonElement? = null,
    val sourceUrl: String? = null,
    val sourcePayload: JsonElement? = null,
    val vendorRefPayload: JsonElement? = null,
    val additionalVendorRefs: List<CatalogVendorRefEtlRecord> = emptyList(),
)

data class CampsiteEtlRecord(
    val vendor: String,
    val vendorRefId: String,
    val parentVendor: String?,
    val parentVendorRefId: String?,
    val name: String,
    val kind: String = DEFAULT_CAMPSITE_KIND,
    val loopName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val reservationUrl: String? = null,
    val equipment: JsonElement? = null,
    val kindListed: String? = null,
    val schedule: JsonElement? = null,
    val price: JsonElement? = null,
    val firepit: Boolean? = null,
    val picnicTable: Boolean? = null,
    val adaAccessible: Boolean? = null,
    val waterHookups: Boolean? = null,
    val electricHookups: Boolean? = null,
    val sewerHookups: Boolean? = null,
    val maxPeople: Int? = null,
    val maxCars: Int? = null,
    val pullThrough: Boolean? = null,
    val drivewayLength: Int? = null,
    val maxRvLength: Int? = null,
    val maxTrailerLength: Double? = null,
    val photos: JsonElement? = null,
    val sourcePayload: JsonElement? = null,
    val vendorRefPayload: JsonElement? = null,
    val additionalVendorRefs: List<CatalogVendorRefEtlRecord> = emptyList(),
)

data class CatalogVendorRefEtlRecord(
    val vendor: String,
    val vendorRefId: String,
    val sourceUrl: String? = null,
    val payload: JsonElement? = null,
)

data class TeslaSuperchargerEtlRecord(
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

data class PlanetFitnessLocationEtlRecord(
    val locationId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: JsonElement? = null,
    val region: String? = null,
    val country: String? = null,
    val phone: String? = null,
    val infoUrl: String? = null,
    val amenities: JsonElement? = null,
    val payload: JsonElement? = null,
)

const val DEFAULT_CAMPSITE_KIND = "site"

const val DEFAULT_TESLA_SITE_STATUS = "open"
