package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.model.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.model.domain.DEFAULT_CAMPSITE_KIND
import ca.floo.roadtrip.model.domain.DataProvider
import ca.floo.roadtrip.model.etl.CampsiteEtlOutput
import ca.floo.roadtrip.model.metadata.ValidationResult
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.SourceEtl
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.JsonObject

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
        val recgovRef = recgovCampsiteVendorRef(raw, id, reservationUrl)
        return CampsiteUpsertCandidate(
            dataProvider = DataProvider.CAMPFLARE,
            dataProviderRef = id,
            bookingProvider = recgovRef?.vendor,
            bookingProviderRef = recgovRef?.vendorRefId,
            parentDataProvider = DataProvider.CAMPFLARE,
            parentDataProviderRef = campgroundId,
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
        )
    }
}
