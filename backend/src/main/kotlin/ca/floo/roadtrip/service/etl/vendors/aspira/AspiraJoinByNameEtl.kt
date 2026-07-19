package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.domain.BookingProvider
import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.domain.DataProvider
import ca.floo.roadtrip.model.etl.CampgroundCampsiteEtlOutput
import ca.floo.roadtrip.model.metadata.ValidationResult
import ca.floo.roadtrip.service.etl.framework.CampgroundCampsiteEtl
import ca.floo.roadtrip.service.etl.framework.InputBundle
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
import kotlinx.serialization.json.longOrNull
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
class AspiraJoinByNameEtl(
    override val etlSlug: String,
    private val dataProviderValue: DataProvider,
    private val aspiraTenant: String,
) : CampgroundCampsiteEtl<AspiraJoinDto> {
    private val log = LoggerFactory.getLogger(javaClass)
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): AspiraJoinDto {
        // Etl-typed inputs deserialize to AspiraLeavesPayload (one expected).
        var leavesPayload: AspiraLeavesPayload? = null
        for (slug in inputs.etlSlugs()) {
            val out = inputs.etlOutput(slug)
            if (out is JsonObject && out["leaves"] != null) {
                leavesPayload = json.decodeFromJsonElement(AspiraLeavesPayload.serializer(), out)
            }
        }
        val leaves = leavesPayload ?: error("$etlSlug: no AspiraLeavesPayload input declared")

        // The inventory + dictionary captures (when declared) drive the
        // non-bookable-node filter, not geometry — pull them out first so
        // detectGeometrySource never sees them.
        val inventorySlug = inputs.dataSourceSlugs().firstOrNull { it.contains("inventory") }
        val dictionarySlug = inputs.dataSourceSlugs().firstOrNull { it.contains("dictionaries") }

        // Remaining data_source-typed inputs become geometry sources, in
        // declaration order so the YAML's `inputs:` order doubles as a
        // preference order.
        val geomEntries =
            inputs
                .dataSourceSlugs()
                .filter { it != inventorySlug && it != dictionarySlug }
                .map { slug -> slug to detectGeometrySource(slug, inputs.envelopes(slug)) }

        return AspiraJoinDto(
            leaves = leaves,
            geomSources = geomEntries,
            inventoryEnvelopes = inventorySlug?.let { inputs.envelopes(it) } ?: emptyList(),
            dictionaryPayload = dictionarySlug?.let { inputs.envelope(it).payload as? JsonObject },
            fetchedAt = Instant.now(),
        )
    }

    override fun validate(dto: AspiraJoinDto): ValidationResult<AspiraJoinDto> {
        val errs = mutableListOf<String>()
        if (dto.leaves.leaves.isEmpty()) errs += "no leaves to join"
        if (dto.geomSources.isEmpty()) errs += "no geometry sources declared"
        return if (errs.isEmpty()) ValidationResult.Ok(dto) else ValidationResult.Bad(null, errs)
    }

    override fun transform(
        dto: AspiraJoinDto,
        ctx: TransformCtx,
    ): CampgroundCampsiteEtlOutput {
        val host = ctx.argFor(etlSlug, "host") ?: error("$etlSlug: missing args.host")
        val subcategory = ctx.subcategoryFor(etlSlug)
        val agency = ctx.requiredConstantAgency(etlSlug)

        // Non-bookable filter, driven by the fetched data (no curated list). A
        // leaf whose resourceLocationId's inventory holds only non-bookable
        // categories — Aspira's own `showResourceCapacityOnline: false`, set on
        // parking / guided hikes / shuttles / day-use buses — is a park
        // activity mount, not a campground, so it is dropped even when its name
        // matches geometry. Empty when a tenant declares no inventory +
        // dictionary inputs, or when its dictionary marks everything bookable
        // (WA/BC today) — then nothing is dropped, faithfully reflecting that
        // that tenant's data marks nothing as non-bookable.
        val nonBookableResLocs =
            AspiraInventoryCategories.nonBookableResourceLocationIds(
                inventory = dto.inventoryEnvelopes,
                dictionaryPayload = dto.dictionaryPayload,
            )
        val bookingCtaRefsByResourceLocationId = canonicalBookingCtaRefs(dto.inventoryEnvelopes, dto.dictionaryPayload)

        // Build one merged name index: normalized name → first (lat, lon).
        // Geometry entries are walked in declared order, so the YAML's
        // `inputs:` order doubles as a preference order — earlier sources
        // (campground-level) win over later sources (park-polygon
        // centroids) when both have the same normalized name.
        val byName = LinkedHashMap<String, Pair<Double, Double>>()
        for ((slug, geomSource) in dto.geomSources) {
            val before = byName.size
            geomSource.indexInto(byName)
            log.info(
                "$etlSlug: geometry input slug={} contributed {} new keys (total={})",
                slug,
                byName.size - before,
                byName.size,
            )
        }

        // Token sets for the Jaccard fallback. Build once.
        val tokenIndex: List<Pair<Set<String>, Pair<Double, Double>>> =
            byName.entries.map { (k, v) -> k.split(' ').toSet() to v }

        val campgrounds = mutableListOf<CampgroundUpsertCandidate>()
        var exact = 0
        var fuzzy = 0
        var viaParent = 0
        var miss = 0
        var skippedContainer = 0
        var skippedNonBookable = 0
        val missSamples = mutableListOf<String>()

        for (leaf in dto.leaves.leaves) {
            // Campground-level model: a POI is one bookable campground node.
            // Aspira's /api/maps also carries park-level container nodes
            // (Banff, Jasper, …) and park-scoped activity mounts. Those have
            // no resourceLocationId — they are not a bookable resource
            // location, so they are not campgrounds. Emitting them layered a
            // duplicate park POI on top of the park's already-correct
            // campground POIs (each real campground is a mapLink carrying its
            // own resourceLocationId). Skip container nodes here; their child
            // campgrounds carry the coordinates and the parent-name fallback
            // keeps every park represented on the map.
            //
            // Tenant-wide (WA/BC/PC share this ETL). Verified against all
            // three /api/maps captures that the only null-resourceLocationId
            // leaves are park containers (Camano Island WA, Wells Gray BC,
            // 23 PC parks), and that resources carry parent refs through real
            // campground leaves — so dropping containers orphans nothing.
            if (leaf.resourceLocationId == null) {
                skippedContainer++
                continue
            }
            // Non-bookable activity node (parking, guided hike, shuttle, …):
            // its resourceLocationId's inventory holds no overnight-stay
            // resource. Drop it — a booking id + a name-match don't make a
            // campground.
            if (leaf.resourceLocationId in nonBookableResLocs) {
                skippedNonBookable++
                continue
            }
            val nk = normalize(leaf.name)
            var coords: Pair<Double, Double>? = byName[nk]
            var matchKind = "exact"

            if (coords == null) {
                val ntoks = nk.split(' ').toSet()
                val best = tokenIndex.maxByOrNull { jaccard(it.first, ntoks) }
                val score = best?.let { jaccard(it.first, ntoks) } ?: 0.0
                if (best != null && score >= FUZZY_THRESHOLD) {
                    coords = best.second
                    matchKind = "fuzzy"
                }
            }

            if (coords == null && leaf.parentName != null) {
                val pk = normalize(leaf.parentName)
                coords = byName[pk]
                if (coords != null) matchKind = "parent"
            }

            if (coords == null) {
                miss++
                if (missSamples.size < 10) missSamples += leaf.name
                continue
            }

            when (matchKind) {
                "exact" -> exact++
                "fuzzy" -> fuzzy++
                "parent" -> viaParent++
            }

            val (lat, lon) = coords
            val vendorRefId = aspiraVendorRefId(leaf)
            val bookingCtaRef = leaf.resourceLocationId?.let { bookingCtaRefsByResourceLocationId[it] }
            campgrounds +=
                CampgroundUpsertCandidate(
                    dataProvider = dataProviderValue,
                    dataProviderRef = vendorRefId,
                    bookingProvider = BookingProvider.ASPIRA,
                    bookingProviderRef = bookingCtaRef?.let { aspiraBookingRef(leaf, it) },
                    name = leaf.name,
                    latitude = lat,
                    longitude = lon,
                    kind = subcategory,
                    location = locationPayload(latitude = lat, longitude = lon),
                    reservationUrl = "https://$host/",
                    links = linksPayload("https://$host/"),
                    management = managementPayload(agency),
                    metadata =
                        leafExtras(
                            leaf = leaf,
                            host = host,
                            matchKind = matchKind,
                            bookingCtaRef = bookingCtaRef,
                        ),
                    sourceUrl = "https://$host/",
                    sourcePayload = aspiraSourcePayload(leaf, matchKind, bookingCtaRef),
                )
        }

        log.info(
            "$etlSlug: {} leaves → {} pois " +
                "(exact={} fuzzy={} parent={} miss={} skippedContainer={} skippedNonBookable={}; sample misses: {})",
            dto.leaves.leaves.size,
            campgrounds.size,
            exact,
            fuzzy,
            viaParent,
            miss,
            skippedContainer,
            skippedNonBookable,
            missSamples.take(5),
        )
        return CampgroundCampsiteEtlOutput(campgrounds = campgrounds, campsites = emptyList())
    }

    private fun aspiraVendorRefId(leaf: AspiraLeaf): String = "$ASPIRA_VENDOR_REF_PREFIX${leaf.transactionLocationId}-${leaf.mapId}"

    private fun aspiraProviderRefPayload(leaf: AspiraLeaf): JsonObject =
        buildJsonObject {
            put(ASPIRA_TRANSACTION_LOCATION_ID_KEY, leaf.transactionLocationId)
            put(ASPIRA_MAP_ID_KEY, leaf.mapId)
            leaf.resourceLocationId?.let { put(ASPIRA_RESOURCE_LOCATION_ID_KEY, it) }
        }

    private fun aspiraSourcePayload(
        leaf: AspiraLeaf,
        matchKind: String,
        bookingCtaRef: AspiraBookingCtaRef?,
    ): JsonObject =
        buildJsonObject {
            put("name", leaf.name)
            put(ASPIRA_TRANSACTION_LOCATION_ID_KEY, leaf.transactionLocationId)
            put(ASPIRA_MAP_ID_KEY, leaf.mapId)
            leaf.resourceLocationId?.let { put(ASPIRA_RESOURCE_LOCATION_ID_KEY, it) }
            leaf.parentName?.let { put("parent_name", it) }
            put("match_kind", matchKind)
            bookingCtaRef?.let { put("booking_cta_provider_ref", aspiraBookingCtaProviderRefPayload(leaf, it)) }
        }

    private fun aspiraBookingRef(
        leaf: AspiraLeaf,
        bookingCtaRef: AspiraBookingCtaRef,
    ): String = "$aspiraTenant:${leaf.transactionLocationId}:${bookingCtaRef.mapId}:${bookingCtaRef.resourceLocationId}"

    private fun aspiraBookingCtaProviderRefPayload(
        leaf: AspiraLeaf,
        bookingCtaRef: AspiraBookingCtaRef,
    ): JsonObject =
        buildJsonObject {
            put(ASPIRA_TRANSACTION_LOCATION_ID_KEY, leaf.transactionLocationId)
            put(ASPIRA_MAP_ID_KEY, bookingCtaRef.mapId)
            put(ASPIRA_RESOURCE_LOCATION_ID_KEY, bookingCtaRef.resourceLocationId)
        }

    private fun locationPayload(
        latitude: Double,
        longitude: Double,
    ): JsonObject =
        buildJsonObject {
            put("latitude", latitude)
            put("longitude", longitude)
        }

    private fun linksPayload(url: String): JsonElement =
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

    private fun leafExtras(
        leaf: AspiraLeaf,
        host: String,
        matchKind: String,
        bookingCtaRef: AspiraBookingCtaRef?,
    ): JsonElement =
        aspiraExtrasJson.encodeToJsonElement(
            AspiraLeafExtrasDto(
                host = host,
                transactionLocationId = leaf.transactionLocationId,
                mapId = leaf.mapId,
                resourceLocationId = leaf.resourceLocationId,
                parentName = leaf.parentName,
                matchKind = matchKind,
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

    private fun canonicalBookingCtaRefs(
        inventory: List<ca.floo.roadtrip.model.metadata.Envelope>,
        dictionaryPayload: JsonObject?,
    ): Map<Long, AspiraBookingCtaRef> {
        val bookableByCategoryId = AspiraInventoryCategories.bookableFlagByCategoryId(dictionaryPayload)
        val refs = mutableMapOf<Long, AspiraBookingCtaRef>()
        for (envelope in inventory) {
            val payload = envelope.payload as? JsonObject ?: continue
            for ((_, raw) in payload) {
                val obj = raw as? JsonObject ?: continue
                if (!AspiraInventoryCategories.isBookableResource(obj, bookableByCategoryId)) continue
                val resourceLocationId = obj.longValue("resourceLocationId") ?: continue
                val mapId = obj.mapIds().minOrNull() ?: continue
                val current = refs[resourceLocationId]
                if (current == null || mapId < current.mapId) {
                    refs[resourceLocationId] = AspiraBookingCtaRef(mapId = mapId, resourceLocationId = resourceLocationId)
                }
            }
        }
        return refs
    }

    private fun JsonObject.longValue(key: String): Long? =
        this[key]
            ?.jsonPrimitive
            ?.longOrNull

    private fun JsonObject.mapIds(): List<Long> =
        (this["mapIds"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.longOrNull }
            ?: emptyList()

    private fun detectGeometrySource(
        slug: String,
        envelopes: List<ca.floo.roadtrip.model.metadata.Envelope>,
    ): GeometrySource {
        // We have a few characteristic shapes; sniff by slug first (cheap)
        // and fall back to payload inspection if the slug is unknown.
        return when {
            slug.contains("uscampgrounds") -> UsCampgroundsCsvSource(envelopes)
            slug.contains("bcparks") -> BcParksStrapiSource(envelopes)
            slug.contains("places") -> ApcaPlacesCentroidSource(envelopes)
            slug.contains("accommodation") -> ApcaAccommodationSource(envelopes)
            else -> GeoJsonFeaturesSource(envelopes, slug)
        }
    }

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            }
        private const val FUZZY_THRESHOLD = 0.5
        private const val ASPIRA_VENDOR_REF_PREFIX = "aspira-"
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

internal data class AspiraBookingCtaRef(
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
