package ca.floo.roadtrip.etl.reserveamerica

import ca.floo.roadtrip.etl.Envelope
import ca.floo.roadtrip.etl.InputBundle
import ca.floo.roadtrip.etl.Poi
import ca.floo.roadtrip.etl.ProviderRef
import ca.floo.roadtrip.etl.SourceEtl
import ca.floo.roadtrip.etl.TransformCtx
import ca.floo.roadtrip.etl.ValidationResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

// ReserveAmerica (Active Network) Alberta Parks → Poi.Campground.
//
// Capture path: data/raw/reserveamerica-abpp/<ts>/{directory-X-NNN.json,
// park-<id>.json}. The directory pages are by-letter index lists; the
// per-park pages carry the actual POI metadata (lat/lon, name, phone,
// photo). We only mine the park-*.json envelopes — directory pages give
// us the parkId list but every fact we need is on the park page itself.
//
// Payload is HTML, parsed via regex against stable Open Graph + microdata
// markup (place:location:latitude/longitude, og:title, itemprop=telephone).
// Brittle to a redesign of shop.albertaparks.ca but cheap and obvious;
// a redesign would shake out as a validation drop, not silent corruption.
class ReserveAmericaEtl : SourceEtl<ReserveAmericaDto, List<Poi.Campground>> {
    override val etlSlug = "reserveamerica-abpp"
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): ReserveAmericaDto {
        val envelopes = inputs.soleEnvelopes()
        require(envelopes.isNotEmpty()) { "$etlSlug: no pages" }
        val parks = mutableListOf<ParsedPark>()
        for (env in envelopes) {
            val partLabel = env.part ?: continue
            if (!partLabel.startsWith("park-")) continue
            val parkId = partLabel.removePrefix("park-").toLongOrNull() ?: continue
            val html = env.payload.jsonPrimitive.content
            parsePark(parkId, html)?.let { parks += it }
        }
        return ReserveAmericaDto(parks = parks, fetchedAt = parseFetchedAt(envelopes.first()))
    }

    override fun validate(dto: ReserveAmericaDto): ValidationResult<ReserveAmericaDto> {
        val errors = mutableListOf<String>()
        if (dto.parks.isEmpty()) errors += "no park pages parsed"
        return if (errors.isEmpty()) ValidationResult.Ok(dto) else ValidationResult.Bad(null, errors)
    }

    override fun transform(
        dto: ReserveAmericaDto,
        ctx: TransformCtx,
    ): List<Poi.Campground> {
        val bucket = ctx.subcategoryFor(etlSlug)
        return dto.parks.map { park ->
            Poi.Campground(
                source = etlSlug,
                sourceId = "ra-${park.parkId}",
                name = park.name,
                geomGeoJson = """{"type":"Point","coordinates":[${park.lon},${park.lat}]}""",
                region = "AB",
                country = "CA",
                phone = park.phone,
                address = null,
                infoUrl = park.infoUrl,
                fetchedAt = dto.fetchedAt,
                lastVerified = null,
                providerRef = ProviderRef.Camis(facilityId = park.parkId.toString()),
                amenities = emptyList(),
                activities = emptyList(),
                sites = null,
                season = null,
                near = null,
                photoUrl = park.photoUrl,
                cellCoverage = null,
                ratingReviews = null,
                subcategory = bucket,
                agency = "Alberta Parks",
                extras = parkExtras(park),
            )
        }
    }

    /**
     * Re-emit the parsed-out HTML scraps as a flat JsonObject so the
     * drawer's "Upstream data" accordion can surface what we know.
     * Upstream is HTML — there's no canonical row-shaped JSON.
     */
    private fun parkExtras(park: ParsedPark): JsonElement =
        buildJsonObject {
            put("park_id", JsonPrimitive(park.parkId))
            put("name", JsonPrimitive(park.name))
            put("latitude", JsonPrimitive(park.lat))
            put("longitude", JsonPrimitive(park.lon))
            park.phone?.let { put("phone", JsonPrimitive(it)) }
            park.photoUrl?.let { put("photo_url", JsonPrimitive(it)) }
            park.infoUrl?.let { put("info_url", JsonPrimitive(it)) }
        }

    private fun parsePark(
        parkId: Long,
        html: String,
    ): ParsedPark? {
        val lat =
            LATITUDE
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.toDoubleOrNull() ?: return null
        val lon =
            LONGITUDE
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.toDoubleOrNull() ?: return null
        val rawTitle = OG_TITLE.find(html)?.groupValues?.get(1) ?: return null
        // Strip the trailing ", AB" the og:title carries on every page.
        val name = rawTitle.removeSuffix(", AB").trim().ifBlank { return null }
        val phone =
            TELEPHONE
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        val photoUrl =
            OG_IMAGE
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.takeIf { it.isNotBlank() }
        // og:url is the page's own canonical link — pulling it out of the
        // HTML keeps the host + querystring shape owned by upstream rather
        // than hardcoded in the ETL.
        val infoUrl =
            OG_URL
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.takeIf { it.isNotBlank() }
        return ParsedPark(
            parkId = parkId,
            name = name,
            lat = lat,
            lon = lon,
            phone = phone,
            photoUrl = photoUrl,
            infoUrl = infoUrl,
        )
    }

    private fun parseFetchedAt(envelope: Envelope): Instant =
        try {
            Instant.parse(envelope.fetchedAt)
        } catch (e: Exception) {
            Instant.now()
        }

    companion object {
        private val LATITUDE = Regex("""place:location:latitude"\s+content='([^']+)'""")
        private val LONGITUDE = Regex("""place:location:longitude"\s+content='([^']+)'""")
        private val OG_TITLE = Regex("""og:title"\s+content='([^']+)'""")
        private val OG_IMAGE = Regex("""og:image"\s+content='([^']+)'""")
        private val OG_URL = Regex("""og:url"\s+content='([^']+)'""")
        private val TELEPHONE = Regex("""itemprop="telephone"[^>]*>([^<]+)""")
    }
}

data class ParsedPark(
    val parkId: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val phone: String?,
    val photoUrl: String?,
    val infoUrl: String?,
)

data class ReserveAmericaDto(
    val parks: List<ParsedPark>,
    val fetchedAt: Instant,
)
