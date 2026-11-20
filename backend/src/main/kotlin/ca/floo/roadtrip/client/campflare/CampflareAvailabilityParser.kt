package ca.floo.roadtrip.client.campflare

import ca.floo.roadtrip.client.AvailabilityPayloadReads
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.UnrecognizedStatusTally
import ca.floo.roadtrip.model.availability.campflare.CampflareAvailability
import ca.floo.roadtrip.model.availability.campflare.CampflareCampgroundAvailability
import ca.floo.roadtrip.model.availability.campflare.CampflareCampsiteAvailability
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

object CampflareAvailabilityParser {
    private val reads = AvailabilityPayloadReads("campflare")

    fun parse(
        payload: JsonObject,
        observedAt: Instant,
    ): CampflareAvailability {
        val unrecognized = UnrecognizedStatusTally()
        val campgrounds =
            reads
                .array(payload["campgrounds"], "campgrounds")
                .mapIndexed { index, raw -> parseCampground(reads.obj(raw, "campgrounds[$index]"), index, unrecognized) }
                .associateBy { it.campgroundId }
        return CampflareAvailability(
            campgrounds = campgrounds,
            observedAt = observedAt,
            unrecognizedStatuses = unrecognized.counts(),
        )
    }

    private fun parseCampground(
        raw: JsonObject,
        index: Int,
        unrecognized: UnrecognizedStatusTally,
    ): CampflareCampgroundAvailability {
        val campgroundId = reads.string(raw["campground_id"], "campgrounds[$index].campground_id")
        val campsiteAvailability =
            reads
                .array(raw["campsite_availability"], "campground $campgroundId campsite_availability")
                .mapIndexed { siteIndex, site ->
                    parseCampsite(
                        reads.obj(site, "campground $campgroundId campsite_availability[$siteIndex]"),
                        campgroundId,
                        siteIndex,
                        unrecognized,
                    )
                }
        return CampflareCampgroundAvailability(
            campgroundId = campgroundId,
            campsiteAvailability = campsiteAvailability,
        )
    }

    private fun parseCampsite(
        raw: JsonObject,
        campgroundId: String,
        siteIndex: Int,
        unrecognized: UnrecognizedStatusTally,
    ): CampflareCampsiteAvailability {
        val campsiteId = reads.string(raw["campsite_id"], "campground $campgroundId campsite_availability[$siteIndex].campsite_id")
        val availability =
            reads
                .obj(raw["availability"], "campsite $campsiteId availability")
                .entries
                .associate { (date, status) ->
                    reads.isoDate(date, "campsite $campsiteId availability date") to classify(campsiteId, date, status, unrecognized)
                }
        return CampflareCampsiteAvailability(campsiteId = campsiteId, availability = availability)
    }

    private fun classify(
        campsiteId: String,
        date: String,
        status: JsonElement,
        unrecognized: UnrecognizedStatusTally,
    ): AvailabilityStatus {
        val raw = reads.string(status, "campsite $campsiteId status on $date")
        return unrecognized.orUnknown(raw, statusOf(raw))
    }

    private fun statusOf(raw: String): AvailabilityStatus? =
        when (raw.trim().lowercase()) {
            "available" -> AvailabilityStatus.AVAILABLE
            "reserved" -> AvailabilityStatus.RESERVED
            "closed" -> AvailabilityStatus.CLOSED
            "first-come-first-serve", "first_come", "first come first serve" -> AvailabilityStatus.FIRST_COME
            "", "not-yet-released", "unknown" -> AvailabilityStatus.UNKNOWN
            else -> null
        }
}
