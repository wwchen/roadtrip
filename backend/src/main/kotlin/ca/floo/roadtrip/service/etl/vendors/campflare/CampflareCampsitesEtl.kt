package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.models.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.models.domain.DEFAULT_CAMPSITE_KIND
import ca.floo.roadtrip.models.etl.CampsiteEtlOutput
import ca.floo.roadtrip.models.metadata.ValidationResult
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.SourceEtl
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CampflareCampsitesEtl : SourceEtl<List<JsonObject>, CampsiteEtlOutput> {
    override val etlSlug = CAMPSITES_ETL_SLUG
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): List<JsonObject> = jsonObjects(inputs.soleEnvelopes())

    override fun validate(dto: List<JsonObject>): ValidationResult<List<JsonObject>> = ValidationResult.Ok(dto)

    override fun transform(
        dto: List<JsonObject>,
        ctx: TransformCtx,
    ): CampsiteEtlOutput =
        CampsiteEtlOutput(
            campsites = dto.mapNotNull(::campsiteRecord),
        )

    private fun campsiteRecord(raw: JsonObject): CampsiteUpsertCandidate? {
        val id = raw.stringField("id") ?: return null
        val campgroundId = raw.stringField("campground_id") ?: return null
        val name = raw.stringField("name") ?: return null
        val kind = raw.stringField("kind") ?: DEFAULT_CAMPSITE_KIND
        val reservationUrl = raw.stringField("reservation_url")
        return CampsiteUpsertCandidate(
            vendor = CAMPFLARE_VENDOR,
            vendorRefId = id,
            parentVendor = CAMPFLARE_VENDOR,
            parentVendorRefId = campgroundId,
            name = name,
            kind = kind,
            loopName = raw.stringField("loop_name"),
            latitude = normalizedLatitude(raw.doubleField("latitude")),
            longitude = normalizedLongitude(raw.doubleField("longitude")),
            reservationUrl = reservationUrl,
            equipment = raw.arrayField("equipment"),
            kindListed = raw.stringField("kind_listed"),
            schedule = raw.objectField("schedule"),
            price = raw.objectField("price"),
            firepit = raw.booleanField("firepit"),
            picnicTable = raw.booleanField("picnic_table"),
            adaAccessible = raw.booleanField("ada_accessible"),
            waterHookups = raw.booleanField("water_hookups"),
            electricHookups = raw.booleanField("electric_hookups"),
            sewerHookups = raw.booleanField("sewer_hookups"),
            maxPeople = raw.intField("max_people"),
            maxCars = raw.intField("max_cars"),
            pullThrough = raw.booleanField("pull_through"),
            drivewayLength = raw.intField("driveway_length"),
            maxRvLength = raw.intField("max_rv_length"),
            maxTrailerLength = raw.doubleField("max_trailer_length"),
            photos = raw.arrayField("photos"),
            sourcePayload = raw,
            vendorRefPayload =
                buildJsonObject {
                    put("campflare_id", id)
                    put("campground_id", campgroundId)
                },
            additionalVendorRefs = listOfNotNull(recgovCampsiteVendorRef(raw, id, reservationUrl)),
        )
    }
}
