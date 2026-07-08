package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.models.metadata.Envelope
import ca.floo.roadtrip.models.metadata.ValidationResult
import ca.floo.roadtrip.service.etl.framework.CampgroundEtlOutput
import ca.floo.roadtrip.service.etl.framework.CampgroundEtlRecord
import ca.floo.roadtrip.service.etl.framework.CampsiteEtlOutput
import ca.floo.roadtrip.service.etl.framework.CampsiteEtlRecord
import ca.floo.roadtrip.service.etl.framework.DEFAULT_CAMPSITE_KIND
import ca.floo.roadtrip.service.etl.framework.CatalogVendorRefEtlRecord
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.SourceEtl
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class CampflareCampgroundsEtl : SourceEtl<List<JsonObject>, CampgroundEtlOutput> {
    override val etlSlug = CAMPGROUNDS_ETL_SLUG
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): List<JsonObject> = jsonObjects(inputs.soleEnvelopes())

    override fun validate(dto: List<JsonObject>): ValidationResult<List<JsonObject>> = ValidationResult.Ok(dto)

    override fun transform(
        dto: List<JsonObject>,
        ctx: TransformCtx,
    ): CampgroundEtlOutput =
        CampgroundEtlOutput(
            campgrounds = dto.mapNotNull(::campgroundRecord),
        )

    private fun campgroundRecord(raw: JsonObject): CampgroundEtlRecord? {
        val id = raw.stringField("id") ?: return null
        val name = raw.stringField("name") ?: return null
        val location = raw.objectField("location") ?: return null
        val latitude = normalizedLatitude(location.doubleField("latitude")) ?: return null
        val longitude = normalizedLongitude(location.doubleField("longitude")) ?: return null
        return CampgroundEtlRecord(
            vendor = CAMPFLARE_VENDOR,
            vendorRefId = id,
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
            links = raw.arrayField("links"),
            photos = raw.arrayField("photos"),
            alerts = raw.arrayField("alerts"),
            price = raw.objectField("price"),
            cellService = raw.objectField("cell_service"),
            management = raw.objectField("management"),
            contact = raw.objectField("contact"),
            connections = raw.objectField("connections"),
            metadata = raw.objectField("metadata"),
            sourceUrl = "$CAMPGROUND_API_URL/$id",
            sourcePayload = raw,
            vendorRefPayload =
                buildJsonObject {
                    put("campflare_id", id)
                    raw.objectField("connections")?.let { put("connections", it) }
                },
            additionalVendorRefs = listOfNotNull(recgovCampgroundVendorRef(raw, id)),
        )
    }
}

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

    private fun campsiteRecord(raw: JsonObject): CampsiteEtlRecord? {
        val id = raw.stringField("id") ?: return null
        val campgroundId = raw.stringField("campground_id") ?: return null
        val name = raw.stringField("name") ?: return null
        val kind = raw.stringField("kind") ?: DEFAULT_CAMPSITE_KIND
        val reservationUrl = raw.stringField("reservation_url")
        return CampsiteEtlRecord(
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

private fun recgovCampgroundVendorRef(
    raw: JsonObject,
    campflareId: String,
): CatalogVendorRefEtlRecord? {
    val recgovId =
        raw
            .objectField("connections")
            ?.stringField("ridb_facility_id")
            ?: recgovCampgroundIdFromUrl(raw.stringField("reservation_url"))
            ?: return null
    return CatalogVendorRefEtlRecord(
        vendor = RECGOV_CAMPGROUND_VENDOR,
        vendorRefId = "$RECGOV_CAMPGROUND_REF_PREFIX$recgovId",
        sourceUrl = raw.stringField("reservation_url"),
        payload =
            buildJsonObject {
                put("recgov_id", recgovId)
                put("campflare_id", campflareId)
            },
    )
}

private fun recgovCampsiteVendorRef(
    raw: JsonObject,
    campflareId: String,
    reservationUrl: String?,
): CatalogVendorRefEtlRecord? {
    val recgovId = recgovCampsiteIdFromUrl(reservationUrl) ?: return null
    return CatalogVendorRefEtlRecord(
        vendor = RECGOV_CAMPSITE_VENDOR,
        vendorRefId = recgovId,
        sourceUrl = reservationUrl,
        payload =
            buildJsonObject {
                put("recgov_id", recgovId)
                put("campflare_id", campflareId)
                raw.stringField("campground_id")?.let { put("campflare_campground_id", it) }
            },
    )
}

private fun jsonObjects(envelopes: List<Envelope>): List<JsonObject> =
    envelopes.flatMap { envelope ->
        envelope.payload.jsonArray.mapNotNull { it as? JsonObject }
    }

private fun JsonObject.stringField(name: String): String? =
    this[name]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != "null" }

private fun JsonObject.doubleField(name: String): Double? =
    this[name]
        ?.jsonPrimitive
        ?.doubleOrNull

private fun JsonObject.intField(name: String): Int? =
    this[name]
        ?.jsonPrimitive
        ?.intOrNull

private fun JsonObject.booleanField(name: String): Boolean? =
    this[name]
        ?.jsonPrimitive
        ?.booleanOrNull

private fun JsonObject.objectField(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.arrayField(name: String): JsonElement? = this[name]?.takeIf { runCatching { it.jsonArray }.isSuccess }

private fun normalizedLatitude(value: Double?): Double? = normalizedCoordinate(value, LATITUDE_MIN, LATITUDE_MAX)

private fun normalizedLongitude(value: Double?): Double? = normalizedCoordinate(value, LONGITUDE_MIN, LONGITUDE_MAX)

private fun normalizedCoordinate(
    value: Double?,
    min: Double,
    max: Double,
): Double? {
    if (value == null) return null
    if (value in min..max) return value
    val scaled = value / E6_COORDINATE_SCALE
    return scaled.takeIf { it in min..max }
}

private const val CAMPFLARE_VENDOR = "campflare"
private const val RECGOV_CAMPGROUND_VENDOR = "federal-campgrounds"
private const val RECGOV_CAMPSITE_VENDOR = "recgov"
private const val RECGOV_CAMPGROUND_REF_PREFIX = "recgov-"
private const val CAMPGROUNDS_ETL_SLUG = "campflare-campgrounds"
private const val CAMPSITES_ETL_SLUG = "campflare-campsites"
private const val CAMPGROUND_API_URL = "https://api.campflare.com/v2/campground"
private const val LATITUDE_MIN = -90.0
private const val LATITUDE_MAX = 90.0
private const val LONGITUDE_MIN = -180.0
private const val LONGITUDE_MAX = 180.0
private const val E6_COORDINATE_SCALE = 1_000_000.0
private val RECGOV_CAMPGROUND_URL = Regex("""/campgrounds/(\d+)""")
private val RECGOV_CAMPSITE_URL = Regex("""/campsites/(\d+)""")

private fun recgovCampgroundIdFromUrl(url: String?): String? = url?.let { RECGOV_CAMPGROUND_URL.find(it)?.groupValues?.get(1) }

private fun recgovCampsiteIdFromUrl(url: String?): String? = url?.let { RECGOV_CAMPSITE_URL.find(it)?.groupValues?.get(1) }
