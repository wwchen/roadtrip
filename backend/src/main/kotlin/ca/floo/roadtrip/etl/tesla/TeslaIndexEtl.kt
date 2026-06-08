package ca.floo.roadtrip.etl.tesla

import ca.floo.roadtrip.etl.Envelope
import ca.floo.roadtrip.etl.InputBundle
import ca.floo.roadtrip.etl.Poi
import ca.floo.roadtrip.etl.SourceEtl
import ca.floo.roadtrip.etl.TransformCtx
import ca.floo.roadtrip.etl.ValidationResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

// Tesla bulk locations feed → Poi.Supercharger.
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
// refresh worker (`make refresh-superchargers`); rows go stale gracefully.
class TeslaIndexEtl : SourceEtl<TeslaIndexDto, List<Poi.Supercharger>> {
    override val etlSlug = "tesla-superchargers"

    override fun parse(inputs: InputBundle): TeslaIndexDto {
        val envelope = inputs.soleEnvelopes().single()
        val raw = json.decodeFromJsonElement(TeslaIndexEnvelope.serializer(), envelope.payload)
        return TeslaIndexDto(rows = raw.data.data, fetchedAt = parseFetchedAt(envelope))
    }

    override fun validate(dto: TeslaIndexDto): ValidationResult<TeslaIndexDto> {
        val errors = mutableListOf<String>()
        if (dto.rows.isEmpty()) errors += "no rows in payload"
        return if (errors.isEmpty()) ValidationResult.Ok(dto) else ValidationResult.Bad(null, errors)
    }

    override fun transform(
        dto: TeslaIndexDto,
        ctx: TransformCtx,
    ): List<Poi.Supercharger> {
        // tesla-locations-raw is laid out as data/raw/tesla-locations-raw/
        // <slug>/<UTC-ts>.json (one subdir per supercharger), which doesn't
        // fit the InputBundle's flat list-of-envelopes contract. Side-load
        // from ctx.rawDir directly; the YAML keeps it as a sibling
        // data_source so fetch + cache-clear flows still address it.
        val locationsDir = File(ctx.rawDir, "tesla-locations-raw")
        return dto.rows.mapNotNull { transformRow(it, dto.fetchedAt, locationsDir) }
    }

    private fun transformRow(
        row: TeslaIndexRow,
        fetchedAt: Instant,
        locationsDir: File,
    ): Poi.Supercharger? {
        val types = row.locationType ?: return null
        if (CHARGER_TYPES.none { it in types }) return null
        val slug = row.locationUrlSlug?.takeIf { it.isNotBlank() } ?: return null
        val lat = row.latitude ?: return null
        val lon = row.longitude ?: return null

        val detail = loadDetail(locationsDir, slug)

        // Detail's `name` is "Woodburn, OR"-style; index `title` is the
        // useless string "locations". Prefer detail; fall back to a
        // slug-derived label so unenriched rows still render usefully.
        val name =
            detail?.name?.takeIf { it.isNotBlank() }
                ?: row.title?.takeIf { it.isNotBlank() && it != "locations" }
                ?: "Supercharger $slug"

        val (region, country) = regionCountryOf(detail)

        return Poi.Supercharger(
            source = etlSlug,
            sourceId = sanitizeSlug(slug),
            name = name,
            geomGeoJson = """{"type":"Point","coordinates":[$lon,$lat]}""",
            region = region,
            country = country,
            phone = null,
            address = addressOf(detail),
            infoUrl = "https://www.tesla.com/findus?location=$slug",
            fetchedAt = fetchedAt,
            lastVerified = null,
            stallCount = detail?.publicStallCount ?: 0,
            maxPowerKw = detail?.maxPowerKw ?: 0,
            facility = detail?.accessType?.takeIf { it.isNotBlank() },
            pricebooks = detail?.effectivePricebooks ?: emptyList(),
        )
    }

    private fun loadDetail(
        locationsDir: File,
        slug: String,
    ): TeslaLocationDetail? {
        val slugDir = File(locationsDir, slug)
        if (!slugDir.isDirectory) return null
        val newest =
            slugDir
                .listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.maxByOrNull { it.name }
                ?: return null
        return runCatching {
            val env = json.decodeFromString(Envelope.serializer(), newest.readText())
            json.decodeFromJsonElement(TeslaDetailEnvelope.serializer(), env.payload).data.data
        }.onFailure { log.warn("tesla-locations parse failed for slug={}: {}", slug, it.message) }.getOrNull()
    }

    private fun regionCountryOf(detail: TeslaLocationDetail?): Pair<String?, String?> {
        val addr = detail?.address ?: return null to null
        return addr.state?.takeIf { it.isNotBlank() } to addr.countryCode?.takeIf { it.isNotBlank() }
    }

    private fun addressOf(detail: TeslaLocationDetail?): ca.floo.roadtrip.etl.Address? {
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
        return ca.floo.roadtrip.etl.Address(
            street = street,
            city = addr.city?.takeIf { it.isNotBlank() },
            state = addr.state?.takeIf { it.isNotBlank() },
            postcode = addr.postalCode?.takeIf { it.isNotBlank() },
            country = addr.countryCode?.takeIf { it.isNotBlank() },
        )
    }

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
        private val log = LoggerFactory.getLogger(TeslaIndexEtl::class.java)
        private val json = Json { ignoreUnknownKeys = true }
        private val CHARGER_TYPES = setOf("supercharger", "megacharger")
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
)

data class TeslaIndexDto(
    val rows: List<TeslaIndexRow>,
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
    val address: TeslaAddress? = null,
    val publicStallCount: Int? = null,
    val maxPowerKw: Int? = null,
    val accessType: String? = null,
    val openToNonTeslas: Boolean? = null,
    // Pricebook entries Tesla returns alongside the location detail. Held
    // as raw JsonElements; the FE knows the shape and renders only the
    // entries it cares about (Tesla CHARGING, first CONGESTION row).
    val effectivePricebooks: List<kotlinx.serialization.json.JsonElement> = emptyList(),
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
