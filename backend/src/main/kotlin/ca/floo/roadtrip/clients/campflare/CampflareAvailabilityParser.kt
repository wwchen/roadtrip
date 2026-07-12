package ca.floo.roadtrip.clients.campflare

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate

object CampflareAvailabilityParser {
    fun parse(
        payload: JsonObject,
        observedAt: Instant,
    ): CampflareAvailability {
        val campgrounds =
            payload["campgrounds"]
                ?.jsonArray
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
                .mapNotNull(::parseCampground)
                .associateBy { it.campgroundId }
        return CampflareAvailability(campgrounds = campgrounds, observedAt = observedAt)
    }

    private fun parseCampground(raw: JsonObject): CampflareCampgroundAvailability? {
        val campgroundId = raw["campground_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val campsiteAvailability =
            raw["campsite_availability"]
                ?.jsonArray
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
                .mapNotNull(::parseCampsite)
        return CampflareCampgroundAvailability(
            campgroundId = campgroundId,
            campsiteAvailability = campsiteAvailability,
        )
    }

    private fun parseCampsite(raw: JsonObject): CampflareCampsiteAvailability? {
        val campsiteId = raw["campsite_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val availability =
            raw["availability"]
                ?.jsonObject
                ?.mapNotNull { (date, status) ->
                    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull() ?: return@mapNotNull null
                    parsedDate to classify(status.jsonPrimitive.contentOrNull)
                }.orEmpty()
                .toMap()
        return CampflareCampsiteAvailability(campsiteId = campsiteId, availability = availability)
    }

    private fun classify(raw: String?): AvailabilityStatus =
        when (raw?.trim()?.lowercase()) {
            "available" -> AvailabilityStatus.AVAILABLE
            "reserved" -> AvailabilityStatus.RESERVED
            "closed" -> AvailabilityStatus.CLOSED
            "first-come-first-serve", "first_come", "first come first serve" -> AvailabilityStatus.FIRST_COME
            "not-yet-released", "unknown" -> AvailabilityStatus.UNKNOWN
            else -> AvailabilityStatus.UNKNOWN
        }
}
