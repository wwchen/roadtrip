package ca.floo.roadtrip.etl.padus

import ca.floo.roadtrip.etl.Envelope
import ca.floo.roadtrip.etl.ParkType
import ca.floo.roadtrip.etl.Poi
import ca.floo.roadtrip.etl.SourceEtl
import ca.floo.roadtrip.etl.TransformCtx
import ca.floo.roadtrip.etl.ValidationResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant

// USGS PAD-US ArcGIS REST → Poi.Park.
//
// Capture path: data/raw/padus-{np,sp}/<ts>/page-NNN.json (paginated).
// Each page is a GeoJSON FeatureCollection. The fetcher uses
// Des_Tp='NP' for national parks and Des_Tp='SP' AND Mang_Type='STAT'
// for state parks; we trust that filter and don't re-check it here.
//
// Geometry comes back simplified (maxAllowableOffset=0.001°, ~111m) so
// the FeatureCollection fits within reasonable parse times. We hand the
// raw geometry JSON to ST_GeomFromGeoJSON at upsert time.
abstract class PadusEtlBase(
    final override val sourceName: String,
    private val parkType: ParkType,
) : SourceEtl<PadusDto, Poi.Park> {
    final override val multiPart: Boolean = true

    override fun parseMulti(envelopes: List<Envelope>): PadusDto {
        require(envelopes.isNotEmpty()) { "$sourceName: no pages" }
        val features = mutableListOf<PadusFeature>()
        for (env in envelopes) {
            val page = json.decodeFromJsonElement(PadusPageDto.serializer(), env.payload)
            features += page.features
        }
        return PadusDto(features = features, fetchedAt = parseFetchedAt(envelopes.first()))
    }

    override fun validate(dto: PadusDto): ValidationResult<PadusDto> {
        val errors = mutableListOf<String>()
        if (dto.features.isEmpty()) errors += "no features in payload"
        return if (errors.isEmpty()) ValidationResult.Ok(dto) else ValidationResult.Bad(null, errors)
    }

    override fun transform(
        dto: PadusDto,
        ctx: TransformCtx,
    ): List<Poi.Park> {
        // PAD-US splits a single logical park (e.g. Yellowstone) into
        // multiple features, one per disjoint boundary polygon. Group by
        // source_id and merge into a GeometryCollection so each park is
        // one row whose geom carries every part.
        return dto.features
            .mapNotNull { f -> deriveKey(f)?.let { it to f } }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (sid, group) -> mergeFeatures(sid, group, dto.fetchedAt) }
    }

    private fun deriveKey(f: PadusFeature): String? {
        val props = f.properties ?: return null
        if (props.unitNm.isNullOrBlank()) return null
        val locNm = props.locNm?.takeIf { it.isNotBlank() }
        val state = props.stateNm?.takeIf { it.isNotBlank() }
        val raw =
            when {
                locNm != null && state != null -> "$state-$locNm"
                locNm != null -> locNm
                else -> props.unitNm
            }
        return slugify(raw).ifBlank { null }
    }

    private fun mergeFeatures(
        sourceId: String,
        group: List<PadusFeature>,
        fetchedAt: Instant,
    ): Poi.Park? {
        val props = group.first().properties ?: return null
        val geometries = group.mapNotNull { it.geometry }
        if (geometries.isEmpty()) return null
        val geomGeoJson =
            if (geometries.size == 1) {
                geometries.first().toString()
            } else {
                """{"type":"GeometryCollection","geometries":[${geometries.joinToString(",") { it.toString() }}]}"""
            }
        return Poi.Park(
            source = sourceName,
            sourceId = sourceId,
            name = props.unitNm ?: return null,
            geomGeoJson = geomGeoJson,
            region = props.stateNm?.takeIf { it.isNotBlank() },
            country = "US",
            phone = null,
            address = null,
            infoUrl = null,
            fetchedAt = fetchedAt,
            lastVerified = null,
            parkType = parkType,
            designation = props.desTp ?: "",
            officialName = props.locNm?.takeIf { it.isNotBlank() },
            acres = props.gisAcres,
        )
    }

    private fun slugify(s: String): String =
        s
            .lowercase()
            .replace(Regex("[^a-z0-9_:-]+"), "-")
            .trim('-')

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

class PadusNpEtl : PadusEtlBase(sourceName = "padus-national-parks", parkType = ParkType.NATIONAL)

class PadusSpEtl : PadusEtlBase(sourceName = "padus-state-parks", parkType = ParkType.STATE)

@Serializable
data class PadusPageDto(
    val features: List<PadusFeature> = emptyList(),
)

@Serializable
data class PadusFeature(
    val properties: PadusProperties? = null,
    // Keep geometry as a raw JsonElement so we don't have to model every
    // GeoJSON shape (Polygon vs MultiPolygon vs GeometryCollection); the
    // upsert hands it straight to ST_GeomFromGeoJSON.
    val geometry: JsonElement? = null,
)

@Serializable
data class PadusProperties(
    @kotlinx.serialization.SerialName("Unit_Nm") val unitNm: String? = null,
    @kotlinx.serialization.SerialName("Loc_Nm") val locNm: String? = null,
    @kotlinx.serialization.SerialName("State_Nm") val stateNm: String? = null,
    @kotlinx.serialization.SerialName("Mang_Name") val mangName: String? = null,
    @kotlinx.serialization.SerialName("Des_Tp") val desTp: String? = null,
    @kotlinx.serialization.SerialName("GIS_Acres") val gisAcres: Double? = null,
)

data class PadusDto(
    val features: List<PadusFeature>,
    val fetchedAt: Instant,
)
