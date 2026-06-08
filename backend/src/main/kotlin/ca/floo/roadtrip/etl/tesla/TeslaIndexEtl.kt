package ca.floo.roadtrip.etl.tesla

import ca.floo.roadtrip.etl.Envelope
import ca.floo.roadtrip.etl.Poi
import ca.floo.roadtrip.etl.SourceEtl
import ca.floo.roadtrip.etl.TransformCtx
import ca.floo.roadtrip.etl.ValidationResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

// Tesla bulk locations feed → Poi.Supercharger.
//
// Capture path: data/raw/tesla-index/<ts>.json (single envelope).
// Upstream: https://www.tesla.com/api/findus/get-locations covers every
// Tesla site globally — superchargers, destination chargers, sales,
// service. We filter to location_type ∈ {supercharger, megacharger}.
//
// stallCount + maxPowerKw need per-slug detail from the tesla-locations
// fetcher (separate ETL); the index only ships lat/lon and a slug. We
// emit stallCount=0, maxPowerKw=0 and let the locations ETL fill them
// in on its merge pass.
class TeslaIndexEtl : SourceEtl<TeslaIndexDto, Poi.Supercharger> {
    override val sourceName = "tesla-index"

    override fun parse(envelope: Envelope): TeslaIndexDto {
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
        val gbId = ctx.governingBodyId("tesla")
        return dto.rows.mapNotNull { transformRow(it, gbId, dto.fetchedAt) }
    }

    private fun transformRow(
        row: TeslaIndexRow,
        gbId: Long,
        fetchedAt: Instant,
    ): Poi.Supercharger? {
        val types = row.locationType ?: return null
        if (CHARGER_TYPES.none { it in types }) return null
        val slug = row.locationUrlSlug?.takeIf { it.isNotBlank() } ?: return null
        val lat = row.latitude ?: return null
        val lon = row.longitude ?: return null

        // The index doesn't ship a name string for superchargers —
        // location_url_slug is the canonical handle and matches the
        // tesla-locations dir name. Fall back to slug-derived label so
        // the row is rendered usefully even before locations enrichment.
        val name = row.title?.takeIf { it.isNotBlank() && it != "locations" } ?: "Supercharger $slug"

        return Poi.Supercharger(
            source = sourceName,
            sourceId = sanitizeSlug(slug),
            name = name,
            geomGeoJson = """{"type":"Point","coordinates":[$lon,$lat]}""",
            region = null,
            country = null,
            governingBodyId = gbId,
            phone = null,
            address = null,
            infoUrl = "https://www.tesla.com/findus?location=$slug",
            fetchedAt = fetchedAt,
            lastVerified = null,
            stallCount = 0,
            maxPowerKw = 0,
            facility = null,
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
