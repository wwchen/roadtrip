package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.models.ProviderRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
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

        obj["facility_id"]?.jsonPrimitive?.contentOrNull?.let {
            return ProviderRef.Camis(facilityId = it)
        }

        return null
    }
}
