package ca.floo.roadtrip.service.etl.vendors.tesla

import ca.floo.roadtrip.models.domain.DEFAULT_TESLA_SITE_STATUS
import ca.floo.roadtrip.models.domain.TeslaSuperchargerUpsertCandidate
import ca.floo.roadtrip.models.metadata.Envelope
import ca.floo.roadtrip.models.metadata.ValidationResult
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.SourceEtl
import ca.floo.roadtrip.service.etl.framework.TeslaSuperchargerEtlOutput
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

// Tesla bulk locations feed → canonical tesla_superchargers.
//
// Capture path: data/raw/tesla-index/<ts>.json (single envelope).
// Upstream: https://www.tesla.com/api/findus/get-locations covers every
// Tesla site globally — superchargers, destination chargers, sales,
// service. We filter to location_type ∈ {supercharger, megacharger}.
//
// Per-site enrichment: when data/raw/tesla-locations/<slug>/<ts>.json
// exists for a row, we read the newest detail capture and pull out the
// nice name ("Woodburn, OR"), street address, public stall count,
// max kW, and access type. Rows without a cached detail page still
// render with placeholder fields — the index alone is enough to put a
// pin on the map. The cache lifetime is governed by the offline
// fetch worker; rows go stale gracefully.
class TeslaIndexEtl : SourceEtl<TeslaIndexDto, TeslaSuperchargerEtlOutput> {
    override val etlSlug = "tesla-superchargers"

    override fun parse(inputs: InputBundle): TeslaIndexDto {
        val envelope = inputs.soleEnvelopes().single()
        val raw = json.decodeFromJsonElement(TeslaIndexEnvelope.serializer(), envelope.payload)
        // Two passes: typed for the hot fields + raw JsonObject by slug
        // for the full payload. Drives tesla_superchargers.index_payload so
        // the drawer's "Upstream data" accordion has every Tesla index field.
        val rawBySlug = mutableMapOf<String, JsonObject>()
        val rawArr =
            envelope.payload.jsonObject["data"]
                ?.jsonObject
                ?.get("data")
                ?.jsonArray
        if (rawArr != null) {
            for (entry in rawArr) {
                val obj = entry.jsonObject
                val slug =
                    obj["location_url_slug"]?.let { kotlin.runCatching { it.jsonPrimitive.content }.getOrNull() }
                        ?: continue
                if (slug.isNotBlank()) rawBySlug[slug] = obj
            }
        }
        return TeslaIndexDto(rows = raw.data.data, rawBySlug = rawBySlug, fetchedAt = parseFetchedAt(envelope))
    }

    override fun validate(dto: TeslaIndexDto): ValidationResult<TeslaIndexDto> {
        val errors = mutableListOf<String>()
        if (dto.rows.isEmpty()) errors += "no rows in payload"
        return if (errors.isEmpty()) ValidationResult.Ok(dto) else ValidationResult.Bad(null, errors)
    }

    override fun transform(
        dto: TeslaIndexDto,
        ctx: TransformCtx,
    ): TeslaSuperchargerEtlOutput {
        // tesla-locations is laid out as data/raw/tesla-locations/
        // <slug>/<UTC-ts>.json (one subdir per supercharger), which doesn't
        // fit the InputBundle's flat list-of-envelopes contract. Side-load
        // from ctx.rawDir directly; the YAML keeps it as a sibling
        // data_source so fetch + cache-clear flows still address it.
        val locationsDir = File(ctx.rawDir, "tesla-locations")
        val superchargers =
            dto.rows.mapNotNull { row ->
                val rawIndex = row.locationUrlSlug?.let { dto.rawBySlug[it] }
                transformRow(row, rawIndex, locationsDir)
            }
        // sanitizeSlug is lossy (case-fold + non-alnum → `-`); two distinct
        // upstream slugs can collapse to the same DB key and overwrite via
        // ON CONFLICT. Detect and fail rather than silently drop rows.
        val bySlug = superchargers.groupBy { it.locationSlug }
        val collisions = bySlug.filterValues { it.size > 1 }
        if (collisions.isNotEmpty()) {
            val sample =
                collisions.entries.take(3).joinToString("; ") { (slug, group) ->
                    "$slug ← [${group.joinToString(", ") { it.commonSiteName }}]"
                }
            error(
                "tesla-index: ${collisions.size} sanitized location_slug collision(s) — " +
                    "distinct upstream slugs collapsing to the same key. Sample: $sample",
            )
        }
        return TeslaSuperchargerEtlOutput(superchargers = superchargers)
    }

    private fun transformRow(
        row: TeslaIndexRow,
        rawIndex: JsonObject?,
        locationsDir: File,
    ): TeslaSuperchargerUpsertCandidate? {
        // Filter mirrors scripts/fetch_tesla_locations.py:na_supercharger_slugs.
        // Trust supercharger_function over location_type — Tesla's bulk index
        // sometimes labels real Supercharger sites with surprising types
        // ("party" for Calgary AB Macleod Trail SE = 4001402, etc.). The
        // metadata block is the load-bearing signal.
        val sf = row.superchargerFunction ?: return null
        if (sf.showOnFindUs == "0") return null
        val slug = row.locationUrlSlug?.takeIf { it.isNotBlank() } ?: return null
        val lat = row.latitude ?: return null
        val lon = row.longitude ?: return null

        val (detail, rawDetail) = loadDetail(locationsDir, slug)

        // Detail's `name` is "Woodburn, OR"-style; index `title` is the
        // useless string "locations". Prefer detail; fall back to a
        // slug-derived label so unenriched rows still render usefully.
        val name =
            detail?.name?.takeIf { it.isNotBlank() }
                ?: row.title?.takeIf { it.isNotBlank() && it != "locations" }
                ?: "Supercharger $slug"

        val (region, country) = regionCountryOf(detail)

        return TeslaSuperchargerUpsertCandidate(
            locationSlug = sanitizeSlug(slug),
            commonSiteName = name,
            latitude = lat,
            longitude = lon,
            locationGuid = detail?.locationGuid?.takeIf { it.isNotBlank() },
            siteStatus = sf.siteStatus?.takeIf { it.isNotBlank() } ?: DEFAULT_TESLA_SITE_STATUS,
            accessType = detail?.accessType?.takeIf { it.isNotBlank() },
            openToPublic = detail?.openToPublic ?: true,
            openToNonTeslas = detail?.openToNonTeslas,
            trailerFriendly = detail?.isTrailerFriendly,
            twentyFourSeven = detail?.accessHours?.twentyFourSeven,
            stallCount = detail?.publicStallCount,
            maxPowerKw = detail?.maxPowerKw,
            address = addressJson(addressOf(detail)),
            region = region,
            country = country,
            timeZone = detail?.timeZone?.takeIf { it.isNotBlank() },
            amenities = rawDetail?.jsonArrayField(AMENITIES_KEY),
            infoUrl = "https://www.tesla.com/findus?location=$slug",
            pricebooks = JsonArray(detail?.effectivePricebooks ?: emptyList()),
            availabilityProfile = rawDetail?.jsonObjectField(AVAILABILITY_PROFILE_KEY),
            indexPayload = rawIndex,
            detailPayload = rawDetail,
        )
    }

    /**
     * Returns (typed detail, raw detail JsonObject). Either component is
     * null when the per-slug capture is missing or unparseable.
     */
    private fun loadDetail(
        locationsDir: File,
        slug: String,
    ): Pair<TeslaLocationDetail?, JsonObject?> {
        val slugDir = File(locationsDir, slug)
        if (!slugDir.isDirectory) return null to null
        val newest =
            slugDir
                .listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.maxByOrNull { it.name }
                ?: return null to null
        return runCatching {
            val env = json.decodeFromString(Envelope.serializer(), newest.readText())
            val typed = json.decodeFromJsonElement(TeslaDetailEnvelope.serializer(), env.payload).data.data
            val raw =
                env.payload.jsonObject["data"]
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonObject
            typed to raw
        }.onFailure { log.warn("tesla-locations parse failed for slug={}: {}", slug, it.message) }.getOrDefault(null to null)
    }

    private fun regionCountryOf(detail: TeslaLocationDetail?): Pair<String?, String?> {
        val addr = detail?.address ?: return null to null
        return addr.state?.takeIf { it.isNotBlank() } to addr.countryCode?.takeIf { it.isNotBlank() }
    }

    private fun addressOf(detail: TeslaLocationDetail?): ca.floo.roadtrip.models.domain.Address? {
        val addr = detail?.address ?: return null
        val street =
            listOfNotNull(addr.streetNumber, addr.street)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
        if (street == null &&
            addr.city.isNullOrBlank() &&
            addr.state.isNullOrBlank() &&
            addr.postalCode.isNullOrBlank() &&
            addr.countryCode.isNullOrBlank()
        ) {
            return null
        }
        return ca.floo.roadtrip.models.domain.Address(
            street = street,
            city = addr.city?.takeIf { it.isNotBlank() },
            state = addr.state?.takeIf { it.isNotBlank() },
            postcode = addr.postalCode?.takeIf { it.isNotBlank() },
            country = addr.countryCode?.takeIf { it.isNotBlank() },
        )
    }

    private fun addressJson(address: ca.floo.roadtrip.models.domain.Address?): JsonElement? {
        address ?: return null
        return buildJsonObject {
            address.street?.let { put("street", it) }
            address.city?.let { put("city", it) }
            address.state?.let { put("state", it) }
            address.postcode?.let { put("postcode", it) }
            address.country?.let { put("country", it) }
        }
    }

    private fun JsonObject.jsonArrayField(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.jsonObjectField(key: String): JsonObject? = this[key] as? JsonObject

    // location_url_slug values include slashes and uppercase ('AmsterdamNL')
    // that the source_id CHECK constraint (^[a-z0-9:_-]+$) rejects.
    private fun sanitizeSlug(s: String): String = s.lowercase().replace(Regex("[^a-z0-9_:-]+"), "-").trim('-')

    private fun parseFetchedAt(envelope: Envelope): Instant =
        try {
            Instant.parse(envelope.fetchedAt)
        } catch (e: Exception) {
            Instant.now()
        }

    companion object {
        private const val AMENITIES_KEY = "amenities"
        private const val AVAILABILITY_PROFILE_KEY = "availabilityProfile"
        private val log = LoggerFactory.getLogger(TeslaIndexEtl::class.java)
        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
data class TeslaIndexEnvelope(
    val data: TeslaIndexInner = TeslaIndexInner(),
)

@Serializable
data class TeslaIndexInner(
    val data: List<TeslaIndexRow> = emptyList(),
)

@Serializable
data class TeslaIndexRow(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val title: String? = null,
    @kotlinx.serialization.SerialName("location_type") val locationType: List<String>? = null,
    @kotlinx.serialization.SerialName("location_url_slug") val locationUrlSlug: String? = null,
    @kotlinx.serialization.SerialName("supercharger_function") val superchargerFunction: TeslaSuperchargerFunction? = null,
)

@Serializable
data class TeslaSuperchargerFunction(
    @kotlinx.serialization.SerialName("show_on_find_us") val showOnFindUs: String? = null,
    @kotlinx.serialization.SerialName("site_status") val siteStatus: String? = null,
)

data class TeslaIndexDto(
    val rows: List<TeslaIndexRow>,
    val rawBySlug: Map<String, JsonObject>,
    val fetchedAt: Instant,
)

// Tesla per-slug detail envelope shape: payload.data.data.{name, address, …}.
@Serializable
data class TeslaDetailEnvelope(
    val data: TeslaDetailInner = TeslaDetailInner(),
)

@Serializable
data class TeslaDetailInner(
    val data: TeslaLocationDetail = TeslaLocationDetail(),
)

@Serializable
data class TeslaLocationDetail(
    val name: String? = null,
    @kotlinx.serialization.SerialName("locationGUID") val locationGuid: String? = null,
    val address: TeslaAddress? = null,
    val timeZone: String? = null,
    val openToPublic: Boolean? = null,
    val publicStallCount: Int? = null,
    val maxPowerKw: Int? = null,
    val accessType: String? = null,
    val openToNonTeslas: Boolean? = null,
    val isTrailerFriendly: Boolean? = null,
    val accessHours: TeslaAccessHours? = null,
    // Pricebook entries Tesla returns alongside the location detail. Held
    // as raw JsonElements; the FE knows the shape and renders only the
    // entries it cares about (Tesla CHARGING, first CONGESTION row).
    val effectivePricebooks: List<JsonElement> = emptyList(),
)

@Serializable
data class TeslaAccessHours(
    val twentyFourSeven: Boolean? = null,
)

@Serializable
data class TeslaAddress(
    val street: String? = null,
    val streetNumber: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val countryCode: String? = null,
)
