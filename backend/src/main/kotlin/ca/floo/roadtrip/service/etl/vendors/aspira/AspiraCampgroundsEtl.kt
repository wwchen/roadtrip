package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.TransformResult
import ca.floo.roadtrip.service.etl.framework.CampgroundEtl
import ca.floo.roadtrip.service.etl.framework.CampgroundJsonb
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant

// Aspira leaves + heterogeneous geometry sources → canonical campgrounds.
//
// `/api/maps` carries booking IDs but no lat/lng (the SPA renders against
// pixel-coord image maps, not geographic). To put a pin on the map we
// have to join Aspira leaves to a sibling source that actually carries
// coordinates. Each tenant has its own pairing:
//
//   WA → uscampgrounds.info CSV (state column 12)
//   BC → BC Parks Strapi (already provincial-shape; protectedAreaName)
//   PC → APCA ArcGIS Accommodation (campground points) + Places
//        (per-park polygon centroids). A campground leaf whose own name
//        misses geometry falls back to its parent park's centroid via
//        parent_name. Park-container leaves themselves are dropped before
//        emission (see the resourceLocationId gate in transform), so the
//        centroid now only backstops campground leaves, never emits a
//        park-level pin.
//
// One ETL class. Inputs are declared in YAML; this class dispatches on
// the slug shape (recognized via the envelope contents) at parse time.
//
// Match strategy: aggressive name normalization (lowercase, drop park /
// campground / national-park-of-canada / etc. suffixes), then exact
// match against the union name → coords index; fallback to ≥0.5
// Jaccard token overlap; final fallback to the leaf's `parent_name`.
// Leaves that can't be matched are dropped — the booking ID alone
// doesn't earn a pin on the map.
class AspiraCampgroundsEtl(
    override val etlSlug: String,
    private val dataProviderValue: DataProvider,
    private val aspiraTenant: String,
    /**
     * Two-letter state a US tenant books, from the registry's `state_filter`.
     * The uscampgrounds.info geometry file is nationwide and campground names
     * repeat across states, so without this a leaf can match another state's
     * row. Null for the non-US tenants, which want the whole file.
     */
    private val stateFilter: String? = null,
) : CampgroundEtl<AspiraJoinDto> {
    private val log = LoggerFactory.getLogger(javaClass)
    override val multiPart: Boolean = true

    /**
     * The geometry inputs, paired with the source that reads each one — every
     * declared input that is not maps, inventory or dictionaries.
     */
    internal fun geometrySourcesFor(inputs: InputBundle): List<Pair<String, GeometrySource>> {
        val slugs = inputs.dataSourceSlugs()
        val mapsSlug = slugs.first { it.contains("maps") }
        val inventorySlug = slugs.firstOrNull { it.contains("inventory") }
        val dictionarySlug = slugs.firstOrNull { it.contains("dictionaries") }
        return slugs
            .filter { it != mapsSlug && it != inventorySlug && it != dictionarySlug }
            .map { slug -> slug to detectGeometrySource(slug, inputs.envelopes(slug)) }
    }

    override fun parse(inputs: InputBundle): Sequence<ParseResult<AspiraJoinDto>> =
        sequence {
            val mapsSlug = inputs.dataSourceSlugs().first { it.contains("maps") }
            val inventorySlug = inputs.dataSourceSlugs().firstOrNull { it.contains("inventory") }
            val dictionarySlug = inputs.dataSourceSlugs().firstOrNull { it.contains("dictionaries") }

            val mapsArray = inputs.envelope(mapsSlug).payload.jsonArray
            val leaves = AspiraLeavesWalk.walk(mapsArray)

            val geomEntries = geometrySourcesFor(inputs)

            val dto =
                AspiraJoinDto(
                    leaves = leaves,
                    geomSources = geomEntries,
                    inventoryEnvelopes = inventorySlug?.let { inputs.envelopes(it) } ?: emptyList(),
                    dictionaryPayload = dictionarySlug?.let { inputs.envelope(it).payload as? JsonObject },
                    fetchedAt = Instant.now(),
                )
            val errs = mutableListOf<String>()
            if (dto.leaves.isEmpty()) errs += "no leaves from /api/maps"
            if (dto.geomSources.isEmpty()) errs += "no geometry sources declared"
            if (errs.isEmpty()) {
                yield(ParseResult.Ok(dto))
            } else {
                yield(ParseResult.Bad(null, errs))
            }
        }

    override fun transform(
        dto: AspiraJoinDto,
        ctx: TransformCtx,
    ): Sequence<TransformResult<CampgroundUpsertCandidate>> {
        val host = ctx.argFor(etlSlug, "host") ?: error("$etlSlug: missing args.host")
        val subcategory = ctx.subcategoryFor(etlSlug)
        val agency = ctx.requiredConstantAgency(etlSlug)

        // Non-bookable filter, driven by the fetched data (no curated list):
        // Aspira's own `showResourceCapacityOnline: false`. Empty when a tenant
        // declares no inventory + dictionary inputs, or when its dictionary
        // marks everything bookable (WA/BC today) — then nothing is dropped.
        val nonBookableResLocs =
            AspiraInventoryCategories.nonBookableResourceLocationIds(
                inventory = dto.inventoryEnvelopes,
                dictionaryPayload = dto.dictionaryPayload,
            )
        val bookableMapIds =
            AspiraBookingCtaRefs.bookableMapIdsByResourceLocationId(dto.inventoryEnvelopes, dto.dictionaryPayload)

        val matcher = AspiraLeafMatcher(indexGeometry(dto.geomSources), nonBookableResLocs)
        val (matches, tally) = matcher.matchBookable(dto.leaves)
        val campgrounds = matches.map { campgroundCandidate(it, host, subcategory, agency, bookableMapIds) }

        log.info(
            "$etlSlug: {} leaves → {} pois " +
                "(exact={} fuzzy={} parent={} miss={} skippedContainer={} skippedNonBookable={}; sample misses: {})",
            dto.leaves.size,
            campgrounds.size,
            tally.exact,
            tally.fuzzy,
            tally.parent,
            tally.miss,
            tally.skippedContainer,
            tally.skippedNonBookable,
            tally.missSamples,
        )
        return campgrounds.asSequence().map { TransformResult.Ok(it) }
    }

    /**
     * One merged name index: normalized name → first (lat, lon). Geometry
     * entries are walked in declared order, so the YAML's `inputs:` order
     * doubles as a preference order — earlier sources (campground-level)
     * win over later sources (park-polygon centroids) when both carry the
     * same normalized name.
     */
    private fun indexGeometry(geomSources: List<Pair<String, GeometrySource>>): Map<String, Pair<Double, Double>> {
        val byName = LinkedHashMap<String, Pair<Double, Double>>()
        for ((slug, geomSource) in geomSources) {
            val before = byName.size
            geomSource.indexInto(byName)
            log.info(
                "$etlSlug: geometry input slug={} contributed {} new keys (total={})",
                slug,
                byName.size - before,
                byName.size,
            )
        }
        return byName
    }

    private fun campgroundCandidate(
        match: AspiraLeafMatch<Pair<Double, Double>>,
        host: String,
        subcategory: String?,
        agency: String,
        bookableMapIds: Map<Long, Set<Long>>,
    ): CampgroundUpsertCandidate {
        val leaf = match.leaf
        val (lat, lon) = match.value
        val dataRef = DataProviderRef.Aspira(transactionLocationId = leaf.transactionLocationId, mapId = leaf.mapId)
        val bookingCtaRef = AspiraBookingCtaRefs.forLeaf(leaf, bookableMapIds)
        return CampgroundUpsertCandidate(
            dataProviderRef = dataRef,
            bookingProvider = BookingProvider.ASPIRA,
            bookingProviderRef = bookingCtaRef?.let { campgroundBookingProviderRef(leaf, it) },
            name = leaf.name,
            latitude = lat,
            longitude = lon,
            kind = subcategory,
            location = CampgroundJsonb.location(latitude = lat, longitude = lon),
            reservationUrl = "https://$host/",
            links = CampgroundJsonb.links("https://$host/"),
            management = CampgroundJsonb.management(agency),
            metadata =
                leafExtras(
                    leaf = leaf,
                    host = host,
                    matchKind = match.kind,
                    bookingCtaRef = bookingCtaRef,
                ),
            sourceUrl = "https://$host/",
            sourcePayload = aspiraSourcePayload(leaf, match.kind, bookingCtaRef),
        )
    }

    private fun campgroundBookingProviderRef(
        leaf: AspiraLeaf,
        bookingCtaRef: AspiraBookingCtaRef,
    ): String =
        BookingProviderRef
            .Aspira(
                tenant = aspiraTenant,
                transactionLocationId = leaf.transactionLocationId,
                mapId = bookingCtaRef.mapId,
                resourceLocationId = bookingCtaRef.resourceLocationId,
            ).serialize()

    private fun aspiraSourcePayload(
        leaf: AspiraLeaf,
        matchKind: AspiraLeafMatchKind,
        bookingCtaRef: AspiraBookingCtaRef?,
    ): JsonObject =
        buildJsonObject {
            put("name", leaf.name)
            put(ASPIRA_TRANSACTION_LOCATION_ID_KEY, leaf.transactionLocationId)
            put(ASPIRA_MAP_ID_KEY, leaf.mapId)
            leaf.resourceLocationId?.let { put(ASPIRA_RESOURCE_LOCATION_ID_KEY, it) }
            leaf.parentName?.let { put("parent_name", it) }
            put("match_kind", matchKind.label)
            bookingCtaRef?.let {
                put(
                    "booking_cta_provider_ref",
                    buildJsonObject {
                        put(ASPIRA_TRANSACTION_LOCATION_ID_KEY, leaf.transactionLocationId)
                        put(ASPIRA_MAP_ID_KEY, it.mapId)
                        put(ASPIRA_RESOURCE_LOCATION_ID_KEY, it.resourceLocationId)
                    },
                )
            }
        }

    private fun leafExtras(
        leaf: AspiraLeaf,
        host: String,
        matchKind: AspiraLeafMatchKind,
        bookingCtaRef: AspiraBookingCtaRef?,
    ): JsonElement =
        aspiraExtrasJson.encodeToJsonElement(
            AspiraLeafExtrasDto(
                host = host,
                transactionLocationId = leaf.transactionLocationId,
                mapId = leaf.mapId,
                resourceLocationId = leaf.resourceLocationId,
                parentName = leaf.parentName,
                matchKind = matchKind.label,
                bookingCtaProviderRef =
                    bookingCtaRef?.let {
                        AspiraBookingCtaProviderRefDto(
                            transactionLocationId = leaf.transactionLocationId,
                            mapId = it.mapId,
                            resourceLocationId = it.resourceLocationId,
                        )
                    },
            ),
        )

    private fun detectGeometrySource(
        slug: String,
        envelopes: List<ca.floo.roadtrip.model.metadata.Envelope>,
    ): GeometrySource {
        // We have a few characteristic shapes; sniff by slug first (cheap)
        // and fall back to payload inspection if the slug is unknown.
        return when {
            slug.contains("uscampgrounds") -> UsCampgroundsCsvSource(envelopes, stateFilter)
            slug.contains("bcparks") -> BcParksStrapiSource(envelopes)
            slug.contains("places") -> ApcaPlacesCentroidSource(envelopes)
            slug.contains("accommodation") -> ApcaAccommodationSource(envelopes)
            else -> GeoJsonFeaturesSource(envelopes, slug)
        }
    }

    companion object {
        private const val ASPIRA_TRANSACTION_LOCATION_ID_KEY = "transactionLocationId"
        private const val ASPIRA_MAP_ID_KEY = "mapId"
        private const val ASPIRA_RESOURCE_LOCATION_ID_KEY = "resourceLocationId"
    }
}

@OptIn(ExperimentalSerializationApi::class)
private val aspiraExtrasJson =
    Json {
        encodeDefaults = true
        explicitNulls = true
    }

@Serializable
private data class AspiraLeafExtrasDto(
    val host: String,
    @SerialName("transaction_location_id") val transactionLocationId: Long,
    @SerialName("map_id") val mapId: Long,
    @SerialName("resource_location_id") val resourceLocationId: Long?,
    @SerialName("parent_name") val parentName: String?,
    @SerialName("match_kind") val matchKind: String,
    @SerialName("booking_cta_provider_ref") val bookingCtaProviderRef: AspiraBookingCtaProviderRefDto?,
)

@Serializable
private data class AspiraBookingCtaProviderRefDto(
    val transactionLocationId: Long,
    val mapId: Long,
    val resourceLocationId: Long,
)

// ---- Helpers ---------------------------------------------------------------

/**
 * Aggressive name normalization. Lowercase, drop punctuation, collapse
 * whitespace, strip park-y suffixes that would block exact-match (state
 * park, provincial park, national park reserve, "of canada", campground,
 * recreation area, …).
 *
 * Sized for the WA / BC / PC / AB tenants we know today; if a future
 * tenant introduces a new suffix we'd just add it here.
 */
internal fun normalize(name: String): String {
    var n = name.lowercase()
    n = n.replace(Regex("[^a-z0-9 ]"), " ")
    val parkCruft =
        Regex(
            """\b(of\s+canada|national\s+park\s+reserve|national\s+park|""" +
                """national\s+historic\s+site|national\s+marine\s+conservation\s+area\s+reserve|""" +
                """national\s+marine\s+conservation\s+area|national\s+marine\s+park|""" +
                """park\s+reserve|state\s+park|state\s+recreation\s+area|state\s+forest|""" +
                """state\s+campground|provincial\s+park|recreation\s+area|park|reserve|""" +
                """campground|campsite|trailer\s+court|village|cabins?|centre|center)\b""",
        )
    n = parkCruft.replace(n, " ")
    n = n.replace(Regex("\\s+"), " ").trim()
    n = n.replace(Regex("\\s*\\b(and|or)\\b\\s*$"), "").trim()
    return n
}

internal fun jaccard(
    a: Set<String>,
    b: Set<String>,
): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    return (a intersect b).size.toDouble() / (a union b).size.toDouble()
}

internal fun csvSplit(line: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    for (c in line) {
        when {
            c == '"' -> inQuotes = !inQuotes
            c == ',' && !inQuotes -> {
                out += sb.toString()
                sb.clear()
            }
            else -> sb.append(c)
        }
    }
    out += sb.toString()
    return out
}
