package ca.floo.roadtrip.service.etl.framework

import kotlinx.serialization.json.JsonElement

/**
 * Canonical output for vendor campsite catalog ETLs.
 *
 * These adapters are intentionally not wired into runnable import phases yet:
 * old `reservable_data` ingestion is disabled until the canonical campground
 * writer lands. Keeping the output in canonical terms lets the vendor parsing
 * code compile and evolve without preserving the removed `reservables` table
 * contract.
 */
data class CampsiteEtlOutput(
    val campsites: List<CampsiteEtlRecord>,
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
)

const val DEFAULT_CAMPSITE_KIND = "site"
