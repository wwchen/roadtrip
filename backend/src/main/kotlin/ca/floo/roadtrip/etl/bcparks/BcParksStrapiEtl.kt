package ca.floo.roadtrip.etl.bcparks

import ca.floo.roadtrip.etl.Envelope
import ca.floo.roadtrip.etl.ParkType
import ca.floo.roadtrip.etl.Poi
import ca.floo.roadtrip.etl.SourceEtl
import ca.floo.roadtrip.etl.TransformCtx
import ca.floo.roadtrip.etl.ValidationResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

// BC Parks Strapi feed → Poi.Park.
//
// Capture path: data/raw/bcparks-strapi/<ts>/page-NNN.json (paginated,
// 100 rows/page). Each row is a "protectedArea" record from BC Parks'
// public Strapi instance, covering Parks, Recreation Areas, Protected
// Areas, and Conservancies. Geometry is a single lat/lon point per row;
// boundary polygons live in a separate dataset and aren't surfaced here.
class BcParksStrapiEtl : SourceEtl<BcParksDto, Poi.Park> {
    override val sourceName = "bcparks-strapi"
    override val multiPart: Boolean = true

    override fun parseMulti(envelopes: List<Envelope>): BcParksDto {
        require(envelopes.isNotEmpty()) { "$sourceName: no pages" }
        val rows = mutableListOf<BcParksRow>()
        for (env in envelopes) {
            val page = json.decodeFromJsonElement(BcParksPageDto.serializer(), env.payload)
            rows += page.data
        }
        return BcParksDto(rows = rows, fetchedAt = parseFetchedAt(envelopes.first()))
    }

    override fun validate(dto: BcParksDto): ValidationResult<BcParksDto> {
        val errors = mutableListOf<String>()
        if (dto.rows.isEmpty()) errors += "no rows in payload"
        return if (errors.isEmpty()) ValidationResult.Ok(dto) else ValidationResult.Bad(null, errors)
    }

    override fun transform(
        dto: BcParksDto,
        ctx: TransformCtx,
    ): List<Poi.Park> {
        val gbId = ctx.governingBodyId("bc-parks")
        return dto.rows.mapNotNull { transformRow(it, gbId, dto.fetchedAt) }
    }

    private fun transformRow(
        row: BcParksRow,
        gbId: Long,
        fetchedAt: Instant,
    ): Poi.Park? {
        // ORCS (Official Records and Conservation System) is the stable
        // BC Parks identifier — survives renames and reorganizations.
        val orcs = row.orcs ?: return null
        val name = row.protectedAreaName?.takeIf { it.isNotBlank() } ?: return null
        val lat = row.latitude ?: return null
        val lon = row.longitude ?: return null
        // Skip de-listed parks. The fetcher's current snapshot only ships
        // Active rows, but be defensive — Strapi could change its filter.
        if (row.legalStatus != null && !row.legalStatus.equals("Active", ignoreCase = true)) return null

        return Poi.Park(
            source = sourceName,
            sourceId = "orcs-$orcs",
            name = name,
            geomGeoJson = """{"type":"Point","coordinates":[$lon,$lat]}""",
            region = "BC",
            country = "CA",
            governingBodyId = gbId,
            phone = row.parkContact?.takeIf { it.isNotBlank() },
            address = null,
            infoUrl = row.url?.takeIf { it.isNotBlank() },
            fetchedAt = fetchedAt,
            lastVerified = null,
            parkType = ParkType.PROVINCIAL,
            // type is "Park" / "Recreation Area" / "Protected Area" /
            // "Conservancy"; class is "A"/"B"/"C"/"N" — pack the BC-Parks-
            // native pair into designation so it survives the round-trip.
            designation = listOfNotNull(row.type, row.parkClass?.let { "Class $it" }).joinToString(" • "),
            officialName = null,
            acres = row.totalArea?.let { it * HECTARE_TO_ACRES },
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

        // BC Parks reports totalArea in hectares; Poi.Park.acres is acres.
        private const val HECTARE_TO_ACRES = 2.47105
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
    val fetchedAt: Instant,
)
