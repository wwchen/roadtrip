package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.model.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.model.domain.provider.BookingAlias
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.TransformResult
import ca.floo.roadtrip.service.etl.framework.CampsiteEtl
import ca.floo.roadtrip.service.etl.framework.CampsiteKinds
import ca.floo.roadtrip.service.etl.framework.HtmlText
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

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
        val rawKind = raw.stringField("kind")
        val reservationUrl = raw.stringField("reservation_url")
        val recgovRef = extractRecgovCampsiteRef(reservationUrl)
        val siteKind = CampsiteKinds.campflare(rawKind)
        val warnings = listOfNotNull(siteKind.warning) + countFields.mapNotNull { raw.countWarning(it) }
        return TransformResult.Ok(
            CampsiteUpsertCandidate(
                dataProviderRef = DataProviderRef.Campflare(id = campsiteId),
                bookingProvider = BookingProvider.CAMPFLARE,
                bookingProviderRef = campsiteId,
                bookingAliases = listOfNotNull(recgovRef?.let { BookingAlias(BookingProvider.RECGOV, it) }),
                parentDataProviderRef = DataProviderRef.Campflare(id = parentId),
                name = name!!,
                kind = siteKind.kind,
                loopName = raw.stringField("loop_name"),
                latitude = normalizedLatitude(raw.doubleField("latitude")),
                longitude = normalizedLongitude(raw.doubleField("longitude")),
                reservationUrl = reservationUrl,
                equipment = campflareEquipment(raw.arrayField("equipment")),
                kindListed = raw.stringField("kind_listed") ?: rawKind,
                schedule = raw.objectField("schedule"),
                price = raw.objectField("price"),
                firepit = raw.booleanField("firepit"),
                picnicTable = raw.booleanField("picnic_table"),
                adaAccessible = raw.booleanField("ada_accessible"),
                waterHookups = raw.booleanField("water_hookups"),
                electricHookups = raw.booleanField("electric_hookups"),
                sewerHookups = raw.booleanField("sewer_hookups"),
                maxPeople = raw.countField(MAX_PEOPLE),
                maxCars = raw.countField(MAX_CARS),
                pullThrough = raw.booleanField("pull_through"),
                drivewayLength = raw.countField(DRIVEWAY_LENGTH),
                maxRvLength = raw.countField(MAX_RV_LENGTH),
                maxTrailerLength = raw.doubleField("max_trailer_length"),
                photos = campflarePhotos(raw.arrayField("photos")),
                description = raw.stringField("description")?.let(HtmlText::stripTags)?.takeIf { it.isNotBlank() },
                sourcePayload = raw,
            ),
            warnings,
        )
    }

    /** Upstream lists equipment either as bare names or as objects keyed `name`. */
    private fun campflareEquipment(equipment: JsonElement?): List<String> =
        equipment?.jsonArray.orEmpty().mapNotNull { entry ->
            when (entry) {
                is JsonPrimitive -> entry.contentOrNull
                is JsonObject -> entry.stringField("name")
                else -> null
            }?.trim()?.takeIf(String::isNotEmpty)
        }
}

private const val MAX_PEOPLE = "max_people"
private const val MAX_CARS = "max_cars"
private const val DRIVEWAY_LENGTH = "driveway_length"
private const val MAX_RV_LENGTH = "max_rv_length"
private val countFields = listOf(MAX_PEOPLE, MAX_CARS, DRIVEWAY_LENGTH, MAX_RV_LENGTH)
