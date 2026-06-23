package ca.floo.roadtrip.service.etl.vendors.reserveamerica

import ca.floo.roadtrip.models.domain.Poi
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.metadata.Envelope
import ca.floo.roadtrip.models.metadata.ValidationResult
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.SourceEtl
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import ca.floo.roadtrip.service.etl.framework.pointGeoJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

// ReserveAmerica (Active Network) tenant park pages → Poi.Campground.
//
// Capture path: data/raw/reserveamerica-<tenant>/<ts>/{directory-X-NNN.json,
// park-<id>.json}. The directory pages are by-letter index lists; the
// per-park pages carry the actual POI metadata (lat/lon, name, phone,
// photo). We only mine the park-*.json envelopes — directory pages give
// us the parkId list but every fact we need is on the park page itself.
//
// Payload is HTML, parsed via regex against stable Open Graph + microdata
// markup (place:location:latitude/longitude, og:title, itemprop=telephone).
// Brittle to an Active Network redesign but cheap and obvious; a redesign
// would shake out as a validation drop, not silent corruption.
class ReserveAmericaEtl(
    override val etlSlug: String = "alberta-provincial",
) : SourceEtl<ReserveAmericaDto, List<Poi.Campground>> {
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
        val settings = ReserveAmericaSettings.from(ctx, etlSlug)
        return dto.parks.map { park ->
            val name = displayName(park.name, settings.titleSuffix)
            Poi.Campground(
                source = etlSlug,
                sourceId = "${settings.sourceIdPrefix}-${park.parkId}",
                name = name,
                geomGeoJson = pointGeoJson(park.lon, park.lat),
                region = settings.region,
                country = settings.country,
                phone = park.phone,
                address = null,
                infoUrl = park.infoUrl,
                fetchedAt = dto.fetchedAt,
                lastVerified = null,
                providerRef = providerRef(settings, park),
                amenities = emptyList(),
                activities = emptyList(),
                sites = null,
                season = null,
                near = null,
                description = park.description,
                photoUrl = park.photoUrl,
                cellCoverage = null,
                ratingReviews = null,
                subcategory = bucket,
                agency = settings.agency,
                extras = parkExtras(park, name, settings.contract),
            )
        }
    }

    /**
     * Re-emit the parsed-out HTML scraps as a flat JsonObject so the
     * drawer's "Upstream data" accordion can surface what we know.
     * Upstream is HTML — there's no canonical row-shaped JSON.
     */
    private fun parkExtras(
        park: ParsedPark,
        name: String,
        contract: String,
    ): JsonElement =
        reserveAmericaExtrasJson.encodeToJsonElement(
            ReserveAmericaParkExtrasDto(
                contract = contract,
                parkId = park.parkId,
                name = name,
                latitude = park.lat,
                longitude = park.lon,
                phone = park.phone,
                photoUrl = park.photoUrl,
                infoUrl = park.infoUrl,
            ),
        )

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
        val name = rawTitle.trim().ifBlank { return null }
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
        val description =
            OG_DESCRIPTION
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.trim()
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
            description = description,
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

    private fun displayName(
        rawName: String,
        titleSuffix: String,
    ): String {
        val trimmed = rawName.trim()
        return if (titleSuffix.isNotBlank() && trimmed.endsWith(titleSuffix)) {
            trimmed.removeSuffix(titleSuffix).trim()
        } else {
            trimmed
        }
    }

    private fun providerRef(
        settings: ReserveAmericaSettings,
        park: ParsedPark,
    ): ProviderRef? =
        when (settings.provider.lowercase()) {
            "reserveamerica" ->
                ProviderRef.ReserveAmerica(
                    contractCode = settings.contract,
                    parkId = park.parkId.toString(),
                )
            "none", "" -> null
            else -> error("$etlSlug: unsupported ReserveAmerica provider='${settings.provider}'")
        }

    companion object {
        private val LATITUDE = Regex("""place:location:latitude"\s+content='([^']+)'""")
        private val LONGITUDE = Regex("""place:location:longitude"\s+content='([^']+)'""")
        private val OG_TITLE = Regex("""og:title"\s+content='([^']+)'""")
        private val OG_DESCRIPTION = Regex("""og:description"\s+content='([^']*)'""")
        private val OG_IMAGE = Regex("""og:image"\s+content='([^']+)'""")
        private val OG_URL = Regex("""og:url"\s+content='([^']+)'""")
        private val TELEPHONE = Regex("""itemprop="telephone"[^>]*>([^<]+)""")
    }
}

private data class ReserveAmericaSettings(
    val contract: String,
    val region: String,
    val country: String,
    val agency: String,
    val provider: String,
    val titleSuffix: String,
    val sourceIdPrefix: String,
) {
    companion object {
        fun from(
            ctx: TransformCtx,
            etlSlug: String,
        ): ReserveAmericaSettings {
            val region = ctx.argFor(etlSlug, "region") ?: "AB"
            return ReserveAmericaSettings(
                contract = ctx.argFor(etlSlug, "contract") ?: "ABPP",
                region = region,
                country = ctx.argFor(etlSlug, "country") ?: "CA",
                agency = ctx.argFor(etlSlug, "agency") ?: "Alberta Parks",
                provider = ctx.argFor(etlSlug, "provider") ?: "reserveamerica",
                titleSuffix = ctx.argFor(etlSlug, "title_suffix") ?: ", $region",
                sourceIdPrefix = ctx.argFor(etlSlug, "source_id_prefix") ?: "ra",
            )
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
private val reserveAmericaExtrasJson =
    Json {
        encodeDefaults = false
        explicitNulls = false
    }

@Serializable
private data class ReserveAmericaParkExtrasDto(
    val contract: String,
    @SerialName("park_id") val parkId: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val phone: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("info_url") val infoUrl: String? = null,
)

data class ParsedPark(
    val parkId: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val phone: String?,
    val description: String?,
    val photoUrl: String?,
    val infoUrl: String?,
)

data class ReserveAmericaDto(
    val parks: List<ParsedPark>,
    val fetchedAt: Instant,
)
