package ca.floo.roadtrip.service.etl.vendors.bcparks

import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.domain.DataProvider
import ca.floo.roadtrip.model.etl.CampgroundEtlOutput
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.ValidationResult
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.SourceEtl
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

// BC Parks Strapi feed → canonical campgrounds.
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
class BcParksStrapiEtl : SourceEtl<BcParksDto, CampgroundEtlOutput> {
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
    ): CampgroundEtlOutput {
        val bucket = ctx.subcategoryFor(etlSlug)
        return CampgroundEtlOutput(
            campgrounds =
                dto.rows.mapNotNull { row ->
                    transformRow(row, dto.rawById[row.orcs], bucket)
                },
        )
    }

    private fun transformRow(
        row: BcParksRow,
        raw: JsonElement?,
        bucket: String?,
    ): CampgroundUpsertCandidate? {
        // ORCS (Official Records and Conservation System) is the stable
        // BC Parks identifier — survives renames and reorganizations.
        val orcs = row.orcs ?: return null
        val name = row.protectedAreaName?.takeIf { it.isNotBlank() } ?: return null
        val lat = row.latitude ?: return null
        val lon = row.longitude ?: return null
        // Skip de-listed parks. The fetcher's current snapshot only ships
        // Active rows, but be defensive — Strapi could change its filter.
        if (row.legalStatus != null && !row.legalStatus.equals("Active", ignoreCase = true)) return null

        val infoUrl = row.url?.takeIf { it.isNotBlank() }
        val photoUrl = parkPhotoUrl(row.parkPhotos)
        return CampgroundUpsertCandidate(
            dataProvider = DataProvider.fromId(etlSlug),
            dataProviderRef = "$ORCS_REF_PREFIX$orcs",
            name = name,
            latitude = lat,
            longitude = lon,
            kind = bucket,
            mediumDescription = row.description?.trim()?.takeIf { it.isNotBlank() },
            location = locationPayload(lat, lon),
            reservationUrl = infoUrl,
            links = infoUrl?.let(::linksPayload),
            photos = photoUrl?.let(::photoPayload),
            management = managementPayload(),
            contact = row.parkContact?.takeIf { it.isNotBlank() }?.let(::contactPayload),
            sourceUrl = infoUrl,
            sourcePayload = raw,
        )
    }

    private fun locationPayload(
        latitude: Double,
        longitude: Double,
    ): JsonObject =
        buildJsonObject {
            put("latitude", latitude)
            put("longitude", longitude)
            put("region", REGION)
            put("country", COUNTRY)
        }

    private fun linksPayload(url: String): JsonArray =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("url", url)
                },
            )
        }

    private fun photoPayload(url: String): JsonArray =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("url", url)
                },
            )
        }

    private fun managementPayload(): JsonObject =
        buildJsonObject {
            put("agency", AGENCY)
        }

    private fun contactPayload(phone: String): JsonObject =
        buildJsonObject {
            put("phone", phone)
        }

    private fun parseFetchedAt(envelope: Envelope): Instant =
        try {
            Instant.parse(envelope.fetchedAt)
        } catch (e: Exception) {
            Instant.now()
        }

    private fun parkPhotoUrl(photos: List<BcParksPhoto>): String? {
        val withUrl = photos.filter { !it.imageUrl.isNullOrBlank() }
        val candidates = withUrl.filter { it.isActive != false }.ifEmpty { withUrl }
        return candidates
            .sortedWith(
                compareByDescending<BcParksPhoto> { it.isFeatured == true }
                    .thenBy { it.sortOrder ?: Int.MAX_VALUE },
            ).firstOrNull()
            ?.imageUrl
            ?.trim()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val AGENCY = "BC Parks"
        private const val REGION = "BC"
        private const val COUNTRY = "CA"
        private const val ORCS_REF_PREFIX = "orcs-"
    }
}
