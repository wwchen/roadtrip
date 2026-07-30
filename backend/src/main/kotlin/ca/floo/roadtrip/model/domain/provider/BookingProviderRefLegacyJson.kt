package ca.floo.roadtrip.model.domain.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The legacy vendor-keyed JSON encoding of a [BookingProviderRef] — the shape
 * the POI detail API has always emitted (`recgov_id`, `campflare_id`, `mapId`,
 * `park_id`, `place_id`, ...). There is no explicit type tag: the presence of a
 * vendor's field is the discriminator, so writer and reader have to agree
 * field-for-field. They agree because they live here, in one object, with one
 * exhaustive `when` each.
 *
 * This is a **wire/persistence format, not a domain format**. Do not "clean it
 * up": rows and API consumers already carry it. [BookingProviderRef.serialize]
 * / [BookingProviderRef.parse] are the typed colon-delimited encoding used for
 * the `booking_provider_ref` column, and are unrelated to this one.
 */
object BookingProviderRefLegacyJson {
    private const val RECGOV_ID = "recgov_id"
    private const val CAMPFLARE_ID = "campflare_id"
    private const val ASPIRA_TRANSACTION_LOCATION_ID = "transactionLocationId"
    private const val ASPIRA_MAP_ID = "mapId"
    private const val ASPIRA_RESOURCE_LOCATION_ID = "resourceLocationId"
    private const val RESERVEAMERICA_CONTRACT_CODE = "contract_code"
    private const val RESERVEAMERICA_PARK_ID = "park_id"

    /** Pre-`park_id` rows wrote the ReserveAmerica park id under this key. */
    private const val RESERVEAMERICA_LEGACY_FACILITY_ID = "facility_id"
    private const val RESERVECALIFORNIA_PLACE_ID = "place_id"
    private const val RESERVECALIFORNIA_FACILITY_IDS = "facility_ids"

    fun toLegacyJson(ref: BookingProviderRef): String = toLegacyJsonObject(ref).toString()

    private fun toLegacyJsonObject(ref: BookingProviderRef): JsonObject =
        when (ref) {
            is BookingProviderRef.RecGov ->
                buildJsonObject {
                    put(RECGOV_ID, ref.facilityId)
                }

            is BookingProviderRef.Campflare ->
                buildJsonObject {
                    put(CAMPFLARE_ID, ref.campgroundId)
                }

            // The tenant is intentionally not written: it is derived from the
            // campground's host at read time, so a persisted copy could go stale.
            is BookingProviderRef.Aspira ->
                buildJsonObject {
                    put(ASPIRA_TRANSACTION_LOCATION_ID, ref.transactionLocationId)
                    put(ASPIRA_MAP_ID, ref.mapId)
                    ref.resourceLocationId?.let { put(ASPIRA_RESOURCE_LOCATION_ID, it) }
                }

            is BookingProviderRef.ReserveAmerica ->
                buildJsonObject {
                    ref.contractCode?.let { put(RESERVEAMERICA_CONTRACT_CODE, it) }
                    put(RESERVEAMERICA_PARK_ID, ref.parkId)
                }

            is BookingProviderRef.ReserveCalifornia ->
                buildJsonObject {
                    put(RESERVECALIFORNIA_PLACE_ID, ref.placeId)
                    put(RESERVECALIFORNIA_FACILITY_IDS, JsonArray(ref.facilityIds.map(::JsonPrimitive)))
                }
        }

    /**
     * Reads back what [toLegacyJson] wrote (plus the legacy `facility_id`
     * spelling of a ReserveAmerica park). Returns null for malformed JSON or a
     * shape that matches no vendor — callers treat that as "no provider ref",
     * never as an error.
     */
    fun fromLegacyJson(json: String): BookingProviderRef? {
        val obj =
            runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
                ?: return null

        obj[RECGOV_ID]?.jsonPrimitive?.contentOrNull?.let {
            return BookingProviderRef.RecGov(facilityId = it)
        }

        obj[CAMPFLARE_ID]?.jsonPrimitive?.contentOrNull?.let {
            return BookingProviderRef.Campflare(campgroundId = it)
        }

        // Aspira: the writer uses Long for both ids; reading as Long avoids the
        // 32-bit truncation that the legacy `Int` parser introduced.
        val mapId = obj[ASPIRA_MAP_ID]?.jsonPrimitive?.longOrNull
        val transactionLocationId = obj[ASPIRA_TRANSACTION_LOCATION_ID]?.jsonPrimitive?.longOrNull
        if (mapId != null && transactionLocationId != null) {
            return BookingProviderRef.Aspira(
                tenant = null,
                transactionLocationId = transactionLocationId,
                mapId = mapId,
                resourceLocationId = obj[ASPIRA_RESOURCE_LOCATION_ID]?.jsonPrimitive?.longOrNull,
            )
        }

        obj[RESERVEAMERICA_PARK_ID]?.jsonPrimitive?.contentOrNull?.let {
            return BookingProviderRef.ReserveAmerica(
                contractCode = obj[RESERVEAMERICA_CONTRACT_CODE]?.jsonPrimitive?.contentOrNull,
                parkId = it,
            )
        }

        obj[RESERVEAMERICA_LEGACY_FACILITY_ID]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.toLongOrNull() != null }
            ?.let {
                return BookingProviderRef.ReserveAmerica(contractCode = null, parkId = it)
            }

        val placeId = obj[RESERVECALIFORNIA_PLACE_ID]?.jsonPrimitive?.longOrNull
        val facilityIds =
            runCatching {
                obj[RESERVECALIFORNIA_FACILITY_IDS]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.longOrNull }
                    .orEmpty()
            }.getOrDefault(emptyList())
        if (placeId != null && facilityIds.isNotEmpty()) {
            return BookingProviderRef.ReserveCalifornia(placeId = placeId, facilityIds = facilityIds)
        }

        return null
    }
}
