package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.domain.DataProvider
import ca.floo.roadtrip.model.etl.CampgroundCampsiteEtlOutput
import ca.floo.roadtrip.model.metadata.ValidationResult
import ca.floo.roadtrip.service.etl.framework.CampgroundCampsiteEtl
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.JsonObject

class CampflareCampgroundsEtl : CampgroundCampsiteEtl<List<JsonObject>> {
    override val etlSlug = CAMPGROUNDS_ETL_SLUG
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): List<JsonObject> = jsonObjects(inputs.soleEnvelopes())

    override fun validate(dto: List<JsonObject>): ValidationResult<List<JsonObject>> = ValidationResult.Ok(dto)

    override fun transform(
        dto: List<JsonObject>,
        ctx: TransformCtx,
    ): CampgroundCampsiteEtlOutput =
        CampgroundCampsiteEtlOutput(
            campgrounds = dto.mapNotNull(::campgroundRecord),
            campsites = emptyList(),
        )

    private fun campgroundRecord(raw: JsonObject): CampgroundUpsertCandidate? {
        val id = raw.stringField("id") ?: return null
        val name = raw.stringField("name") ?: return null
        val location = raw.objectField("location") ?: return null
        val latitude = normalizedLatitude(location.doubleField("latitude")) ?: return null
        val longitude = normalizedLongitude(location.doubleField("longitude")) ?: return null
        val sourceUrl = campflareCampgroundSourceUrl(id)
        val recgovRef = recgovCampgroundVendorRef(raw, id)
        return CampgroundUpsertCandidate(
            dataProvider = DataProvider.CAMPFLARE,
            dataProviderRef = id,
            bookingProvider = recgovRef?.vendor,
            bookingProviderRef = recgovRef?.vendorRefId,
            name = name,
            latitude = latitude,
            longitude = longitude,
            status = raw.stringField("status"),
            kind = raw.stringField("kind"),
            location = location,
            defaultCampsiteSchedule = raw.objectField("default_campsite_schedule"),
            amenities = raw.objectField("amenities"),
            maxRvLength = raw.doubleField("max_rv_length"),
            maxTrailerLength = raw.doubleField("max_trailer_length"),
            hasPullThroughSites = raw.booleanField("has_pull_through_sites"),
            bigRigFriendly = raw.booleanField("big_rig_friendly"),
            reservationUrl = raw.stringField("reservation_url"),
            links = campgroundLinksWithCampflareSource(raw, sourceUrl),
            photos = raw.arrayField("photos"),
            alerts = raw.arrayField("alerts"),
            price = raw.objectField("price"),
            cellService = raw.objectField("cell_service"),
            management = raw.objectField("management"),
            contact = raw.objectField("contact"),
            connections = raw.objectField("connections"),
            metadata = raw.objectField("metadata"),
            sourceUrl = sourceUrl,
            sourcePayload = raw,
        )
    }
}
