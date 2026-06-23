package ca.floo.roadtrip.service.etl.vendors.recgov

import ca.floo.roadtrip.models.domain.Address
import ca.floo.roadtrip.models.domain.CellSignal
import ca.floo.roadtrip.models.domain.Poi
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.RatingSummary
import ca.floo.roadtrip.models.metadata.Envelope
import ca.floo.roadtrip.models.metadata.ValidationResult
import ca.floo.roadtrip.models.metadata.registry.AgencyConfig
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.SourceEtl
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import ca.floo.roadtrip.service.etl.framework.pointGeoJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.math.round

// RIDB facilities feed → Poi.Campground.
//
// Capture path: data/raw/<slug>/<ts>/page-NNN.json. Each page is the
// envelope-wrapped RIDB /organizations/<orgId>/facilities response (a
// RECDATA array of Facility records). Multi-part read concatenates
// every page into one logical capture.
//
// One ETL class covers every RIDB-publishing agency. Agency identity comes
// from the configured facility raw field and lands on Poi.Campground.agency.
// Reservable facilities get provider_ref=RecGov(FacilityID); non-reservable
// facilities remain useful map POIs but are not live availability targets.
class RecGovCampgroundsEtl(
    override val etlSlug: String,
) : SourceEtl<RecGovDto, List<Poi.Campground>> {
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): RecGovDto {
        val envelopes = inputs.envelopes(RIDB_INPUT)
        require(envelopes.isNotEmpty()) { "$etlSlug: no pages" }
        // Two passes per envelope: typed parse for the hot fields
        // (FacilityID, lat/lng, name) and raw JsonObject for the full
        // payload — we hand the latter to Poi.Campground.extras so the
        // drawer sees every field RIDB ships, even ones the ETL didn't
        // explicitly promote.
        val typed = mutableListOf<Facility>()
        val rawById = mutableMapOf<Long, JsonObject>()
        for (env in envelopes) {
            val page = json.decodeFromJsonElement(RidbPageDto.serializer(), env.payload)
            typed += page.RECDATA
            val rawArr = env.payload.jsonObject["RECDATA"]?.jsonArray ?: continue
            for (entry in rawArr) {
                val obj = entry.jsonObject
                val id =
                    obj["FacilityID"]?.let { v ->
                        kotlin.runCatching { v.toString().trim('"').toLong() }.getOrNull()
                    } ?: continue
                rawById[id] = obj
            }
        }
        val enrichmentById =
            if (ENRICHMENT_INPUT in inputs.dataSourceSlugs()) {
                parseEnrichment(inputs.envelopes(ENRICHMENT_INPUT))
            } else {
                emptyMap()
            }
        return RecGovDto(
            rows = typed,
            rawById = rawById,
            enrichmentById = enrichmentById,
            fetchedAt = parseFetchedAt(envelopes.first()),
        )
    }

    override fun validate(dto: RecGovDto): ValidationResult<RecGovDto> {
        val errors = mutableListOf<String>()
        if (dto.rows.isEmpty()) errors += "no rows in payload"
        return if (errors.isEmpty()) ValidationResult.Ok(dto) else ValidationResult.Bad(null, errors)
    }

    override fun transform(
        dto: RecGovDto,
        ctx: TransformCtx,
    ): List<Poi.Campground> {
        val bucket = ctx.subcategoryFor(etlSlug)
        val agencyConfig = ctx.agencyFor(etlSlug)
        return dto.rows.mapNotNull {
            transformRow(
                row = it,
                raw = dto.rawById[it.FacilityID],
                enrichment = dto.enrichmentById[it.FacilityID],
                fetchedAt = dto.fetchedAt,
                bucket = bucket,
                agencyConfig = agencyConfig,
            )
        }
    }

    private fun transformRow(
        row: Facility,
        raw: JsonElement?,
        enrichment: JsonObject?,
        fetchedAt: Instant,
        bucket: String?,
        agencyConfig: AgencyConfig?,
    ): Poi.Campground? {
        // RIDB ships ORGANIZATION per row when full=true. The registry decides
        // which field becomes the user-facing agency label.
        val rawObj = raw as? JsonObject
        val reservable = isReservable(rawObj)
        val agency = agencyFrom(rawObj, agencyConfig)
        val name = row.FacilityName?.takeIf { it.isNotBlank() } ?: return null
        val lat = row.FacilityLatitude
        val lon = row.FacilityLongitude
        // Some RIDB rows ship 0,0 for missing geometry; reject those —
        // a dot in the Atlantic is worse than no dot.
        if (lat == null || lon == null || (lat == 0.0 && lon == 0.0)) return null

        val firstAddr = row.FACILITYADDRESS?.firstOrNull()
        val address =
            firstAddr?.let {
                Address(
                    street = it.FacilityStreetAddress1?.takeIf { s -> s.isNotBlank() },
                    city = it.City?.takeIf { s -> s.isNotBlank() },
                    state = it.AddressStateCode?.takeIf { s -> s.isNotBlank() },
                    postcode = it.PostalCode?.takeIf { s -> s.isNotBlank() },
                    country = normalizeCountry(it.AddressCountryCode),
                )
            }

        return Poi.Campground(
            source = etlSlug,
            sourceId = "recgov-${row.FacilityID}",
            name = name,
            geomGeoJson = pointGeoJson(lon, lat),
            region = address?.state ?: firstAddr?.AddressStateCode,
            country = address?.country ?: "US",
            phone = row.FacilityPhone?.takeIf { it.isNotBlank() },
            address = address,
            infoUrl = facilityInfoUrl(row, rawObj, reservable),
            fetchedAt = fetchedAt,
            lastVerified = null,
            providerRef =
                if (reservable) {
                    ProviderRef.RecGov(recgovId = row.FacilityID.toString())
                } else {
                    null
                },
            amenities = emptyList(),
            activities = activities(rawObj),
            sites = null,
            season = null,
            near = null,
            description = description(rawObj),
            photoUrl = photoUrl(rawObj),
            cellCoverage = cellCoverage(enrichment),
            ratingReviews = ratingSummary(enrichment),
            subcategory = bucket,
            agency = agency,
            extras = raw,
        )
    }

    private fun agencyFrom(
        raw: JsonObject?,
        agencyConfig: AgencyConfig?,
    ): String? =
        when (agencyConfig) {
            is AgencyConfig.Constant -> agencyConfig.value
            is AgencyConfig.DerivedFromField -> raw?.stringAtRawPath(agencyConfig.field)
            null -> null
        }

    private fun parseEnrichment(envelopes: List<Envelope>): Map<Long, JsonObject> {
        val out = mutableMapOf<Long, JsonObject>()
        for (env in envelopes) {
            val payload = env.payload as? JsonObject ?: continue
            val id =
                payload["facility_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?: (payload["aggregate"] as? JsonObject)
                        ?.get("location_id")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.toLongOrNull()
                    ?: continue
            out[id] = payload
        }
        return out
    }

    private fun activities(raw: JsonObject?): List<String> {
        val rows = raw?.get("ACTIVITY")?.jsonArray ?: return emptyList()
        return rows
            .mapNotNull { entry ->
                val name =
                    entry.jsonObject["ActivityName"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                normalizeActivity(name)
            }.distinct()
    }

    private fun normalizeActivity(name: String): String =
        name
            .replace("&", "and")
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    private fun description(raw: JsonObject?): String? =
        raw
            ?.get("FacilityDescription")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun photoUrl(raw: JsonObject?): String? {
        val media =
            raw
                ?.get("MEDIA")
                ?.jsonArray
                ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                ?.filter { media ->
                    val mediaType = media["MediaType"]?.jsonPrimitive?.contentOrNull
                    mediaType == null || mediaType.equals("Image", ignoreCase = true)
                }
                ?: return null
        return media.firstOrNull { it.jsonBool("IsPrimary") }?.url()
            ?: media.firstOrNull { it.jsonBool("IsPreview") }?.url()
            ?: media.firstNotNullOfOrNull { it.url() }
    }

    private fun JsonObject.jsonBool(key: String): Boolean {
        val primitive = get(key)?.jsonPrimitive ?: return false
        return primitive.booleanOrNull == true || primitive.contentOrNull.equals("true", ignoreCase = true)
    }

    private fun ratingSummary(enrichment: JsonObject?): RatingSummary? {
        val agg = aggregate(enrichment) ?: return null
        val count = agg.int("number_of_ratings")
        val avg = agg.float("average_rating")
        if (count == null || count <= 0 || avg == null) return null
        return RatingSummary(avg = round2(avg), count = count)
    }

    private fun cellCoverage(enrichment: JsonObject?): Map<String, CellSignal>? {
        val agg = aggregate(enrichment) ?: return null
        val rows = agg["aggregate_cell_coverage_ratings"]?.jsonArray ?: return null
        val out = linkedMapOf<String, CellSignal>()
        for (entry in rows) {
            val obj = runCatching { entry.jsonObject }.getOrNull() ?: continue
            val carrier = CARRIER_SLUG[obj["carrier"]?.jsonPrimitive?.contentOrNull] ?: continue
            val count = obj.int("number_of_ratings")
            val avg = obj.float("average_rating")
            if (count == null || count <= 0 || avg == null) continue
            out[carrier] = CellSignal(avg = round2(avg), count = count)
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun aggregate(enrichment: JsonObject?): JsonObject? = (enrichment?.get("aggregate") as? JsonObject) ?: enrichment

    private fun JsonObject.int(key: String): Int? =
        get(key)
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toIntOrNull()

    private fun JsonObject.float(key: String): Float? =
        get(key)
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toFloatOrNull()

    private fun round2(value: Float): Float = (round(value * 100f) / 100f)

    private fun facilityInfoUrl(
        row: Facility,
        raw: JsonObject?,
        reservable: Boolean,
    ): String? =
        row.FacilityReservationURL?.takeIf { it.isNotBlank() }
            ?: if (reservable) {
                "https://www.recreation.gov/camping/campgrounds/${row.FacilityID}"
            } else {
                officialFacilityUrl(raw)
            }

    private fun officialFacilityUrl(raw: JsonObject?): String? {
        val links =
            raw
                ?.get("LINK")
                ?.jsonArray
                ?.mapNotNull { entry -> runCatching { entry.jsonObject }.getOrNull() }
                ?: return null
        return links.firstNotNullOfOrNull { link ->
            val linkType = link["LinkType"]?.jsonPrimitive?.contentOrNull
            if (linkType.equals("Official Web Site", ignoreCase = true)) link.url() else null
        } ?: links.firstNotNullOfOrNull { it.url() }
    }

    private fun JsonObject.url(): String? =
        get("URL")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }

    private fun isReservable(raw: JsonObject?): Boolean {
        val primitive = raw?.get("Reservable")?.jsonPrimitive ?: return false
        return primitive.booleanOrNull == true || primitive.contentOrNull.equals("true", ignoreCase = true)
    }

    /**
     * RIDB returns ISO 3166-1 alpha-3 ("USA") in AddressCountryCode, but
     * the pois.country column is alpha-2 (CHAR(2)). Map known agencies'
     * codes; null when blank or unrecognized so downstream falls back to
     * the ETL's static default ("US" for RIDB sources).
     */
    private fun normalizeCountry(raw: String?): String? {
        val v = raw?.trim()?.uppercase() ?: return null
        return when (v) {
            "" -> null
            "USA", "US" -> "US"
            "CAN", "CA" -> "CA"
            else -> if (v.length == 2) v else null
        }
    }

    private fun parseFetchedAt(envelope: Envelope): Instant =
        try {
            Instant.parse(envelope.fetchedAt)
        } catch (e: Exception) {
            Instant.now()
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val RIDB_INPUT = "recgov-campgrounds"
        private const val ENRICHMENT_INPUT = "recgov-campground-enrichment"
        private val CARRIER_SLUG =
            mapOf(
                "Verizon" to "verizon",
                "AT&T" to "att",
                "T-Mobile" to "tmobile",
                "Sprint" to "sprint",
            )
    }
}

private fun JsonObject.stringAtRawPath(path: String): String? {
    var current: JsonElement = this
    for (segment in path.split(RAW_PATH_SEPARATOR)) {
        val match = RAW_PATH_SEGMENT.matchEntire(segment) ?: return null
        val field = match.groupValues[RAW_PATH_FIELD_GROUP]
        val index = match.groupValues[RAW_PATH_INDEX_GROUP].takeIf { it.isNotBlank() }?.toIntOrNull()
        val obj = current as? JsonObject ?: return null
        current = obj[field] ?: return null
        if (index != null) {
            val arr = current as? JsonArray ?: return null
            current = arr.getOrNull(index) ?: return null
        }
    }
    val scalar = current as? JsonPrimitive ?: return null
    return scalar.contentOrNull?.takeIf { it.isNotBlank() }
}

private const val RAW_PATH_SEPARATOR = "."
private const val RAW_PATH_FIELD_GROUP = 1
private const val RAW_PATH_INDEX_GROUP = 2
private val RAW_PATH_SEGMENT = Regex("""([^\[\]]+)(?:\[(\d+)])?""")

// RIDB page envelope: { METADATA: {...}, RECDATA: [Facility, ...] }.
// Field names match RIDB's PascalCase verbatim — kotlinx-serialization
// would normally complain, but the fields aren't generic enough to
// alias, so we suppress and live with the naming noise.
@Suppress("ConstructorParameterNaming", "PropertyName")
@Serializable
data class RidbPageDto(
    val RECDATA: List<Facility> = emptyList(),
)

@Suppress("ConstructorParameterNaming", "PropertyName")
@Serializable
data class Facility(
    val FacilityID: Long,
    val FacilityName: String? = null,
    val FacilityLatitude: Double? = null,
    val FacilityLongitude: Double? = null,
    val FacilityPhone: String? = null,
    val FacilityReservationURL: String? = null,
    val FACILITYADDRESS: List<FacilityAddress>? = null,
)

@Suppress("ConstructorParameterNaming", "PropertyName")
@Serializable
data class FacilityAddress(
    val FacilityStreetAddress1: String? = null,
    val City: String? = null,
    val AddressStateCode: String? = null,
    val PostalCode: String? = null,
    val AddressCountryCode: String? = null,
)

data class RecGovDto(
    val rows: List<Facility>,
    val rawById: Map<Long, JsonObject>,
    val enrichmentById: Map<Long, JsonObject>,
    val fetchedAt: Instant,
)
