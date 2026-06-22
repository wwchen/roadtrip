package ca.floo.roadtrip.service.reservation

import ca.floo.roadtrip.models.domain.ProviderRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One place that parses the `provider_ref` JSONB column. Mirrors the writer
 * in [ca.floo.roadtrip.repo.PoiRepo.providerRefToJson] — presence of a field
 * is the discriminator, no explicit type tag.
 *
 * Returns null for unknown shapes / malformed JSON. Adapters and routes
 * branch on the returned [ProviderRef] variant; nobody else parses JSON.
 */
object ProviderRefParser {
    fun parse(json: String): ProviderRef? {
        val obj =
            runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
                ?: return null

        obj["recgov_id"]?.jsonPrimitive?.contentOrNull?.let {
            return ProviderRef.RecGov(recgovId = it)
        }

        // Aspira: writer uses Long for both ids; reading as Long avoids the
        // 32-bit truncation that the legacy `Int` parser introduced.
        val mapId = obj["mapId"]?.jsonPrimitive?.longOrNull
        val transactionLocationId = obj["transactionLocationId"]?.jsonPrimitive?.longOrNull
        if (mapId != null && transactionLocationId != null) {
            val resourceLocationId = obj["resourceLocationId"]?.jsonPrimitive?.longOrNull
            return ProviderRef.Aspira(
                transactionLocationId = transactionLocationId,
                mapId = mapId,
                resourceLocationId = resourceLocationId,
            )
        }

        obj["park_id"]?.jsonPrimitive?.contentOrNull?.let {
            return ProviderRef.ReserveAmerica(
                contractCode = obj["contract_code"]?.jsonPrimitive?.contentOrNull,
                parkId = it,
            )
        }

        obj["facility_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.toLongOrNull() != null }?.let {
            return ProviderRef.ReserveAmerica(contractCode = null, parkId = it)
        }

        val placeId = obj["place_id"]?.jsonPrimitive?.longOrNull
        val facilityIds =
            runCatching {
                obj["facility_ids"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.longOrNull }
                    .orEmpty()
            }.getOrDefault(emptyList())
        if (placeId != null && facilityIds.isNotEmpty()) {
            return ProviderRef.ReserveCalifornia(placeId = placeId, facilityIds = facilityIds)
        }

        return null
    }
}
