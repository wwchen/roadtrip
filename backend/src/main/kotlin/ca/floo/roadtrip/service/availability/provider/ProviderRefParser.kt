package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
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
 * branch on the returned [BookingProviderRef] variant; nobody else parses JSON.
 */
object ProviderRefParser {
    fun parse(json: String): BookingProviderRef? {
        val obj =
            runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
                ?: return null

        obj["recgov_id"]?.jsonPrimitive?.contentOrNull?.let {
            return BookingProviderRef.RecGov(facilityId = it)
        }

        obj["campflare_id"]?.jsonPrimitive?.contentOrNull?.let {
            return BookingProviderRef.Campflare(campgroundId = it)
        }

        // Aspira: writer uses Long for both ids; reading as Long avoids the
        // 32-bit truncation that the legacy `Int` parser introduced.
        val mapId = obj["mapId"]?.jsonPrimitive?.longOrNull
        val transactionLocationId = obj["transactionLocationId"]?.jsonPrimitive?.longOrNull
        if (mapId != null && transactionLocationId != null) {
            val resourceLocationId = obj["resourceLocationId"]?.jsonPrimitive?.longOrNull
            return BookingProviderRef.Aspira(
                tenant = null,
                transactionLocationId = transactionLocationId,
                mapId = mapId,
                resourceLocationId = resourceLocationId,
            )
        }

        obj["park_id"]?.jsonPrimitive?.contentOrNull?.let {
            return BookingProviderRef.ReserveAmerica(
                contractCode = obj["contract_code"]?.jsonPrimitive?.contentOrNull,
                parkId = it,
            )
        }

        obj["facility_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.toLongOrNull() != null }?.let {
            return BookingProviderRef.ReserveAmerica(contractCode = null, parkId = it)
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
            return BookingProviderRef.ReserveCalifornia(placeId = placeId, facilityIds = facilityIds)
        }

        return null
    }
}

internal fun BookingProviderRef.bookingProvider(): ca.floo.roadtrip.model.domain.provider.BookingProvider =
    when (this) {
        is BookingProviderRef.RecGov -> ca.floo.roadtrip.model.domain.provider.BookingProvider.RECGOV
        is BookingProviderRef.Campflare -> ca.floo.roadtrip.model.domain.provider.BookingProvider.CAMPFLARE
        is BookingProviderRef.Aspira -> ca.floo.roadtrip.model.domain.provider.BookingProvider.ASPIRA
        is BookingProviderRef.ReserveAmerica -> ca.floo.roadtrip.model.domain.provider.BookingProvider.RESERVEAMERICA
        is BookingProviderRef.ReserveCalifornia -> ca.floo.roadtrip.model.domain.provider.BookingProvider.RESERVECALIFORNIA
    }
