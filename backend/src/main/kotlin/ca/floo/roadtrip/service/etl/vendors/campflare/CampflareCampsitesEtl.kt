package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.model.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.model.domain.DEFAULT_CAMPSITE_KIND
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.TransformResult
import ca.floo.roadtrip.service.etl.framework.CampsiteEtl
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.JsonObject

class CampflareCampsitesEtl : CampsiteEtl<JsonObject> {
    override val etlSlug = CAMPSITES_ETL_SLUG
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): Sequence<ParseResult<JsonObject>> = jsonObjectResults(inputs.soleEnvelopes(), etlSlug)

    override fun transform(
        dto: JsonObject,
        ctx: TransformCtx,
    ): Sequence<TransformResult<CampsiteUpsertCandidate>> = sequenceOf(campsiteRecord(dto))

    private fun campsiteRecord(raw: JsonObject): TransformResult<CampsiteUpsertCandidate> {
        val id = raw.stringField("id")
        val campgroundId = raw.stringField("campground_id")
        val name = raw.stringField("name")
        val errors = mutableListOf<String>()
        if (id == null) errors += "missing id"
        if (campgroundId == null) errors += "missing campground_id"
        if (name == null) errors += "missing name"
        if (errors.isNotEmpty()) {
            return TransformResult.Bad(id, errors)
        }

        val campsiteId = id!!
        val parentId = campgroundId!!
        val kind = raw.stringField("kind") ?: DEFAULT_CAMPSITE_KIND
        val reservationUrl = raw.stringField("reservation_url")
        val recgovRef = recgovCampsiteVendorRef(raw, campsiteId, reservationUrl)
        return TransformResult.Ok(
            CampsiteUpsertCandidate(
                dataProviderRef = DataProviderRef.Campflare(id = campsiteId),
                bookingProvider = recgovRef?.vendor ?: BookingProvider.CAMPFLARE,
                bookingProviderRef = recgovRef?.vendorRefId ?: campsiteId,
                parentDataProviderRef = DataProviderRef.Campflare(id = parentId),
                name = name!!,
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
            ),
        )
    }
}
