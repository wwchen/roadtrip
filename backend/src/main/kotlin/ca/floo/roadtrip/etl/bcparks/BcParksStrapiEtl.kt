package ca.floo.roadtrip.etl.bcparks

import ca.floo.roadtrip.etl.Envelope
import ca.floo.roadtrip.etl.InputBundle
import ca.floo.roadtrip.etl.Poi
import ca.floo.roadtrip.etl.SourceEtl
import ca.floo.roadtrip.etl.TransformCtx
import ca.floo.roadtrip.etl.ValidationResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

// BC Parks Strapi feed → Poi.Campground (provincial bucket).
//
// Capture path: data/raw/bcparks-strapi/<ts>/page-NNN.json (paginated,
// 100 rows/page). Each row is a "protectedArea" record from BC Parks'
// public Strapi instance, covering Parks, Recreation Areas, Protected
// Areas, and Conservancies. Geometry is a single lat/lon point per row;
// boundary polygons live in a separate dataset and aren't surfaced here.
//
// Bucketed under campground/provincial (vs the older state-park
// categorization) so BC Parks dots show up alongside Alberta Parks +
// US federal/state campgrounds on the same FE legend layer.
class BcParksStrapiEtl : SourceEtl<BcParksDto, List<Poi.Campground>> {
    override val etlSlug = "bcparks-strapi"
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): BcParksDto {
        val envelopes = inputs.soleEnvelopes()
        require(envelopes.isNotEmpty()) { "$etlSlug: no pages" }
        // Two passes per envelope: typed for the hot fields (orcs, name,
        // lat/lng) and raw JsonObject keyed by ORCS for the full payload —
        // the drawer's "Upstream data" accordion reads it through Poi.*.extras.
        val typed = mutableListOf<BcParksRow>()
        val rawById = mutableMapOf<Long, JsonObject>()
        for (env in envelopes) {
            val page = json.decodeFromJsonElement(BcParksPageDto.serializer(), env.payload)
            typed += page.data
            val rawArr = env.payload.jsonObject["data"]?.jsonArray ?: continue
            for (entry in rawArr) {
                val obj = entry.jsonObject
                val id =
                    obj["orcs"]?.let { v ->
                        kotlin.runCatching { v.jsonPrimitive.content.toLong() }.getOrNull()
                    } ?: continue
                rawById[id] = obj
            }
        }
        return BcParksDto(rows = typed, rawById = rawById, fetchedAt = parseFetchedAt(envelopes.first()))
    }

    override fun validate(dto: BcParksDto): ValidationResult<BcParksDto> {
        val errors = mutableListOf<String>()
        if (dto.rows.isEmpty()) errors += "no rows in payload"
        return if (errors.isEmpty()) ValidationResult.Ok(dto) else ValidationResult.Bad(null, errors)
    }

    override fun transform(
        dto: BcParksDto,
        ctx: TransformCtx,
    ): List<Poi.Campground> {
        val bucket = ctx.subcategoryFor(etlSlug)
        return dto.rows.mapNotNull { row ->
            transformRow(row, dto.rawById[row.orcs], dto.fetchedAt, bucket)
        }
    }

    private fun transformRow(
        row: BcParksRow,
        raw: JsonElement?,
        fetchedAt: Instant,
        bucket: String?,
    ): Poi.Campground? {
        // ORCS (Official Records and Conservation System) is the stable
        // BC Parks identifier — survives renames and reorganizations.
        val orcs = row.orcs ?: return null
        val name = row.protectedAreaName?.takeIf { it.isNotBlank() } ?: return null
        val lat = row.latitude ?: return null
        val lon = row.longitude ?: return null
        // Skip de-listed parks. The fetcher's current snapshot only ships
        // Active rows, but be defensive — Strapi could change its filter.
        if (row.legalStatus != null && !row.legalStatus.equals("Active", ignoreCase = true)) return null

        return Poi.Campground(
            source = etlSlug,
            sourceId = "orcs-$orcs",
            name = name,
            geomGeoJson = """{"type":"Point","coordinates":[$lon,$lat]}""",
            region = "BC",
            country = "CA",
            phone = row.parkContact?.takeIf { it.isNotBlank() },
            address = null,
            infoUrl = row.url?.takeIf { it.isNotBlank() },
            fetchedAt = fetchedAt,
            lastVerified = null,
            // BC Parks routes through Aspira NextGen (camping.bcparks.ca)
            // for bookings; the per-park transactionLocationId/mapId are
            // a separate fetch and aren't on this Strapi row.
            providerRef = null,
            amenities = emptyList(),
            activities = emptyList(),
            sites = null,
            season = null,
            near = null,
            photoUrl = null,
            cellCoverage = null,
            ratingReviews = null,
            subcategory = bucket,
            extras = raw,
        )
    }

    private fun parseFetchedAt(envelope: Envelope): Instant =
        try {
            Instant.parse(envelope.fetchedAt)
        } catch (e: Exception) {
            Instant.now()
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
data class BcParksPageDto(
    val data: List<BcParksRow> = emptyList(),
)

@Serializable
data class BcParksRow(
    val orcs: Long? = null,
    val protectedAreaName: String? = null,
    val type: String? = null,
    @kotlinx.serialization.SerialName("class") val parkClass: String? = null,
    val totalArea: Double? = null,
    val legalStatus: String? = null,
    val url: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val parkContact: String? = null,
)

data class BcParksDto(
    val rows: List<BcParksRow>,
    val rawById: Map<Long, JsonObject>,
    val fetchedAt: Instant,
)
