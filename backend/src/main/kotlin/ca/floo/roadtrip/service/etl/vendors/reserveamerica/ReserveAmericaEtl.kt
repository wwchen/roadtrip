package ca.floo.roadtrip.service.etl.vendors.reserveamerica

import ca.floo.roadtrip.model.domain.BookingProvider
import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.domain.DataProvider
import ca.floo.roadtrip.model.etl.CampgroundEtlOutput
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.ValidationResult
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.SourceEtl
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

// ReserveAmerica (Active Network) tenant park pages → canonical campgrounds.
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
) : SourceEtl<ReserveAmericaDto, CampgroundEtlOutput> {
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
    ): CampgroundEtlOutput {
        val bucket = ctx.subcategoryFor(etlSlug)
        val settings = ReserveAmericaSettings.from(ctx, etlSlug)
        return CampgroundEtlOutput(
            campgrounds =
                dto.parks.map { park ->
                    val name = displayName(park.name, settings.titleSuffix)
                    val vendorRefId = "${settings.sourceIdPrefix}-${park.parkId}"
                    val parkExtras = parkExtras(park, name, settings.contract)
                    CampgroundUpsertCandidate(
                        dataProvider = DataProvider.RESERVEAMERICA,
                        dataProviderRef = vendorRefId,
                        bookingProvider =
                            if (settings.provider.lowercase() == "reserveamerica") {
                                BookingProvider.RESERVEAMERICA
                            } else {
                                null
                            },
                        bookingProviderRef =
                            if (settings.provider.lowercase() == "reserveamerica") {
                                "${settings.contract}:${park.parkId}"
                            } else {
                                null
                            },
                        name = name,
                        latitude = park.lat,
                        longitude = park.lon,
                        kind = bucket,
                        mediumDescription = park.description,
                        location = locationPayload(park, settings),
                        reservationUrl = park.infoUrl,
                        links = park.infoUrl?.let(::linksPayload),
                        photos = park.photoUrl?.let(::photoPayload),
                        management = managementPayload(settings.agency),
                        metadata = parkExtras,
                        sourceUrl = park.infoUrl,
                        sourcePayload = parkExtras,
                    )
                },
        )
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
                description = park.description,
                photoUrl = park.photoUrl,
                infoUrl = park.infoUrl,
            ),
        )

    private fun parsePark(
        parkId: Long,
        html: String,
    ): ParsedPark? {
        val lat =
            latitudeRegex
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.toDoubleOrNull() ?: return null
        val lon =
            longitudeRegex
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.toDoubleOrNull() ?: return null
        val rawTitle = ogTitleRegex.find(html)?.groupValues?.get(1) ?: return null
        val name = rawTitle.trim().ifBlank { return null }
        val phone =
            telephoneRegex
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        val photoUrl =
            ogImageRegex
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.takeIf { it.isNotBlank() }
        val description =
            ogDescriptionRegex
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        // og:url is the page's own canonical link — pulling it out of the
        // HTML keeps the host + querystring shape owned by upstream rather
        // than hardcoded in the ETL.
        val infoUrl =
            ogUrlRegex
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

    private fun locationPayload(
        park: ParsedPark,
        settings: ReserveAmericaSettings,
    ): JsonObject =
        buildJsonObject {
            put("latitude", park.lat)
            put("longitude", park.lon)
            put("region", settings.region)
            put("country", settings.country)
        }

    private fun linksPayload(url: String): JsonElement =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("url", url)
                },
            )
        }

    private fun photoPayload(url: String): JsonElement =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("url", url)
                },
            )
        }

    private fun managementPayload(agency: String): JsonObject =
        buildJsonObject {
            put("agency", agency)
        }

    companion object {
        private val latitudeRegex = Regex("""place:location:latitude"\s+content='([^']+)'""")
        private val longitudeRegex = Regex("""place:location:longitude"\s+content='([^']+)'""")
        private val ogTitleRegex = Regex("""og:title"\s+content='([^']+)'""")
        private val ogDescriptionRegex = Regex("""og:description"\s+content='([^']*)'""")
        private val ogImageRegex = Regex("""og:image"\s+content='([^']+)'""")
        private val ogUrlRegex = Regex("""og:url"\s+content='([^']+)'""")
        private val telephoneRegex = Regex("""itemprop="telephone"[^>]*>([^<]+)""")
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
                agency = ctx.requiredConstantAgency(etlSlug),
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
    val description: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("info_url") val infoUrl: String? = null,
)
