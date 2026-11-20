package ca.floo.roadtrip.client.campflare

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.UnrecognizedStatusTally
import ca.floo.roadtrip.model.availability.campflare.CampflareAvailability
import ca.floo.roadtrip.model.availability.campflare.CampflareCampgroundAvailability
import ca.floo.roadtrip.model.availability.campflare.CampflareCampsiteAvailability
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
        val unrecognized = UnrecognizedStatusTally()
        val campgrounds =
            payload["campgrounds"]
                ?.jsonArray
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
                .mapNotNull { parseCampground(it, unrecognized) }
                .associateBy { it.campgroundId }
        return CampflareAvailability(
            campgrounds = campgrounds,
            observedAt = observedAt,
            unrecognizedStatuses = unrecognized.counts(),
        )
    }

    private fun parseCampground(
        raw: JsonObject,
        unrecognized: UnrecognizedStatusTally,
    ): CampflareCampgroundAvailability? {
        val campgroundId = raw["campground_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val campsiteAvailability =
            raw["campsite_availability"]
                ?.jsonArray
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
                .mapNotNull { parseCampsite(it, unrecognized) }
        return CampflareCampgroundAvailability(
            campgroundId = campgroundId,
            campsiteAvailability = campsiteAvailability,
        )
    }

    private fun parseCampsite(
        raw: JsonObject,
        unrecognized: UnrecognizedStatusTally,
    ): CampflareCampsiteAvailability? {
        val campsiteId = raw["campsite_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val availability =
            raw["availability"]
                ?.jsonObject
                ?.mapNotNull { (date, status) ->
                    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull() ?: return@mapNotNull null
                    val rawStatus = status.jsonPrimitive.contentOrNull
                    parsedDate to unrecognized.orUnknown(rawStatus.orEmpty(), classify(rawStatus))
                }.orEmpty()
                .toMap()
        return CampflareCampsiteAvailability(campsiteId = campsiteId, availability = availability)
    }

    private fun classify(raw: String?): AvailabilityStatus? =
        when (raw?.trim()?.lowercase()) {
            "available" -> AvailabilityStatus.AVAILABLE
            "reserved" -> AvailabilityStatus.RESERVED
            "closed" -> AvailabilityStatus.CLOSED
            "first-come-first-serve", "first_come", "first come first serve" -> AvailabilityStatus.FIRST_COME
            null, "", "not-yet-released", "unknown" -> AvailabilityStatus.UNKNOWN
            else -> null
        }
}
