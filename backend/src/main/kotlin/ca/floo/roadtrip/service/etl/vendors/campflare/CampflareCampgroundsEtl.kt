package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.TransformResult
import ca.floo.roadtrip.service.etl.framework.CampgroundEtl
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.JsonObject

class CampflareCampgroundsEtl : CampgroundEtl<JsonObject> {
    override val etlSlug = CAMPGROUNDS_ETL_SLUG
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): Sequence<ParseResult<JsonObject>> = jsonObjectResults(inputs.soleEnvelopes(), etlSlug)

    override fun transform(
        dto: JsonObject,
        ctx: TransformCtx,
    ): Sequence<TransformResult<CampgroundUpsertCandidate>> = sequenceOf(campgroundRecord(dto))

    private fun campgroundRecord(raw: JsonObject): TransformResult<CampgroundUpsertCandidate> {
        val id = raw.stringField("id")
        val name = raw.stringField("name")
        val location = raw.objectField("location")
        val latitude = normalizedLatitude(location?.doubleField("latitude"))
        val longitude = normalizedLongitude(location?.doubleField("longitude"))
        val errors = mutableListOf<String>()
        if (id == null) errors += "missing id"
        if (name == null) errors += "missing name"
        if (location == null) errors += "missing location"
        if (latitude == null) errors += "missing or invalid latitude"
        if (longitude == null) errors += "missing or invalid longitude"
        if (errors.isNotEmpty()) {
            return TransformResult.Bad(id, errors)
        }

        val campgroundId = id!!
        val sourceUrl = campflareCampgroundSourceUrl(id)
        val recgovRef = extractRecgovCampgroundRef(raw)
        return TransformResult.Ok(
            CampgroundUpsertCandidate(
                dataProviderRef = DataProviderRef.Campflare(id = campgroundId),
                bookingProvider =
                    recgovRef
                        ?.let { BookingProvider.RECGOV }
                        ?: BookingProvider.CAMPFLARE,
                bookingProviderRef = recgovRef ?: campgroundId,
                name = name!!,
                latitude = latitude!!,
                longitude = longitude!!,
                status = raw.stringField("status"),
                kind = raw.stringField("kind"),
                location = campflareLocation(location!!, latitude = latitude!!, longitude = longitude!!),
                defaultCampsiteSchedule = raw.objectField("default_campsite_schedule"),
                amenities = raw.objectField("amenities"),
                maxRvLength = raw.doubleField("max_rv_length"),
                maxTrailerLength = raw.doubleField("max_trailer_length"),
                hasPullThroughSites = raw.booleanField("has_pull_through_sites"),
                bigRigFriendly = raw.booleanField("big_rig_friendly"),
                reservationUrl = raw.stringField("reservation_url"),
                links = campflareLinks(raw.arrayField("links"), sourceUrl),
                photos = campflarePhotos(raw.arrayField("photos")),
                alerts = raw.arrayField("alerts"),
                price = raw.objectField("price"),
                cellService = raw.objectField("cell_service"),
                management = campflareManagement(raw.objectField("management")),
                contact = campflareContact(raw.objectField("contact")),
                connections = raw.objectField("connections"),
                metadata = raw.objectField("metadata"),
                sourceUrl = sourceUrl,
                sourcePayload = raw,
            ),
        )
    }
}
