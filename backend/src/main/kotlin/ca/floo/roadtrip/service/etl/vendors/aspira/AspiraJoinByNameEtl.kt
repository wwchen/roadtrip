package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.models.domain.Poi
import ca.floo.roadtrip.models.domain.ProviderRef
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Instant

// Aspira leaves + heterogeneous geometry sources → Poi.Campground.
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
) : SourceEtl<AspiraJoinDto, List<Poi.Campground>> {
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
    ): List<Poi.Campground> {
        val host = ctx.argFor(etlSlug, "host") ?: error("$etlSlug: missing args.host")
        val subcategory = ctx.subcategoryFor(etlSlug)
        val agency = ctx.requiredConstantAgency(etlSlug)

        // Config-driven non-bookable filter. A leaf whose resourceLocationId's
        // inventory is made up entirely of non-bookable categories (Parking,
        // Guided Hike, Shuttle, Day Use Bus, …) is a park activity mount, not a
        // campground — drop it even though its name may match geometry. The
        // category taxonomy is tenant-specific, so the blocklist comes from the
        // `non_bookable_categories` arg and the inventory + dictionary inputs.
        // A tenant that declares neither gets an empty set and is unaffected —
        // this stays input-driven, never branches on the tenant slug.
        val nonBookableCategories =
            ctx
                .argFor(etlSlug, "non_bookable_categories")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                .orEmpty()
        val nonBookableResLocs =
            AspiraInventoryCategories.nonBookableResourceLocationIds(
                inventory = dto.inventoryEnvelopes,
                dictionaryPayload = dto.dictionaryPayload,
                nonBookableCategories = nonBookableCategories,
            )

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

        val pois = mutableListOf<Poi.Campground>()
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
            // 23 PC parks), and that none of them link a reservable via the
            // joiner's source_id (Rule A) or resourceLocationId (Rule B)
            // rules — so dropping them orphans nothing.
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
            pois +=
                Poi.Campground(
                    source = etlSlug,
                    sourceId = "aspira-${leaf.transactionLocationId}-${leaf.mapId}",
                    name = leaf.name,
                    geomGeoJson = pointGeoJson(lon, lat),
                    region = null,
                    country = null,
                    phone = null,
                    address = null,
                    infoUrl = "https://$host/",
                    fetchedAt = dto.fetchedAt,
                    lastVerified = null,
                    providerRef =
                        ProviderRef.Aspira(
                            transactionLocationId = leaf.transactionLocationId,
                            mapId = leaf.mapId,
                            resourceLocationId = leaf.resourceLocationId,
                        ),
                    amenities = emptyList(),
                    activities = emptyList(),
                    sites = null,
                    season = null,
                    near = null,
                    photoUrl = null,
                    cellCoverage = null,
                    ratingReviews = null,
                    subcategory = subcategory,
                    agency = agency,
                    extras = leafExtras(leaf, host, matchKind),
                )
        }

        log.info(
            "$etlSlug: {} leaves → {} pois " +
                "(exact={} fuzzy={} parent={} miss={} skippedContainer={} skippedNonBookable={}; sample misses: {})",
            dto.leaves.leaves.size,
            pois.size,
            exact,
            fuzzy,
            viaParent,
            miss,
            skippedContainer,
            skippedNonBookable,
            missSamples.take(5),
        )
        return pois
    }

    private fun leafExtras(
        leaf: AspiraLeaf,
        host: String,
        matchKind: String,
    ): JsonElement =
        aspiraExtrasJson.encodeToJsonElement(
            AspiraLeafExtrasDto(
                host = host,
                transactionLocationId = leaf.transactionLocationId,
                mapId = leaf.mapId,
                resourceLocationId = leaf.resourceLocationId,
                parentName = leaf.parentName,
                matchKind = matchKind,
            ),
        )

    private fun detectGeometrySource(
        slug: String,
        envelopes: List<ca.floo.roadtrip.models.metadata.Envelope>,
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
)

// ---- DTO + per-source strategies ------------------------------------------

data class AspiraJoinDto(
    val leaves: AspiraLeavesPayload,
    val geomSources: List<Pair<String, GeometrySource>>,
    /** Per-park `/api/resourcelocation/resources` envelopes, empty when the tenant declares no inventory input. */
    val inventoryEnvelopes: List<ca.floo.roadtrip.models.metadata.Envelope> = emptyList(),
    /** Tenant `/api/resourcecategory` dictionary payload, null when not declared. */
    val dictionaryPayload: JsonObject? = null,
    val fetchedAt: Instant,
)

/**
 * Each geometry input knows how to extract (name → lat/lon) tuples from
 * its envelope shape and seed them into a shared index.
 */
sealed interface GeometrySource {
    fun indexInto(byName: MutableMap<String, Pair<Double, Double>>)
}

/** uscampgrounds.info — the payload is a CSV string. State is column 12. */
class UsCampgroundsCsvSource(
    private val envelopes: List<ca.floo.roadtrip.models.metadata.Envelope>,
) : GeometrySource {
    override fun indexInto(byName: MutableMap<String, Pair<Double, Double>>) {
        for (env in envelopes) {
            val text = env.payload.jsonPrimitive.contentOrNull ?: continue
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                val cols = csvSplit(line)
                if (cols.size < 13) continue
                val lon = cols[0].toDoubleOrNull() ?: continue
                val lat = cols[1].toDoubleOrNull() ?: continue
                val name = cols.getOrNull(4)?.trim().orEmpty()
                if (name.isEmpty()) continue
                val key = normalize(name)
                if (key.isNotEmpty()) byName.putIfAbsent(key, lat to lon)
            }
        }
    }
}

/** BC Parks Strapi — paginated JSON pages, rows under payload.data[]. */
class BcParksStrapiSource(
    private val envelopes: List<ca.floo.roadtrip.models.metadata.Envelope>,
) : GeometrySource {
    override fun indexInto(byName: MutableMap<String, Pair<Double, Double>>) {
        for (env in envelopes) {
            val rows = env.payload.jsonObject["data"]?.jsonArray ?: continue
            for (row in rows) {
                val o = row.jsonObject
                val name = o["protectedAreaName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                val lat = o["latitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                val lon = o["longitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                val key = normalize(name)
                if (key.isNotEmpty()) byName.putIfAbsent(key, lat to lon)
            }
        }
    }
}

/** APCA Accommodation feature service — geojson features with Point geometry, attribute Name_e. */
class ApcaAccommodationSource(
    private val envelopes: List<ca.floo.roadtrip.models.metadata.Envelope>,
) : GeometrySource {
    override fun indexInto(byName: MutableMap<String, Pair<Double, Double>>) {
        for (env in envelopes) {
            val feats = env.payload.jsonObject["features"]?.jsonArray ?: continue
            for (f in feats) {
                val o = f.jsonObject
                val props = o["properties"]?.jsonObject ?: continue
                val name = props["Name_e"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                val geom = o["geometry"]?.jsonObject ?: continue
                if (geom["type"]?.jsonPrimitive?.contentOrNull != "Point") continue
                val coords = geom["coordinates"]?.jsonArray ?: continue
                if (coords.size < 2) continue
                val lon = coords[0].jsonPrimitive.contentOrNull?.toDoubleOrNull() ?: continue
                val lat = coords[1].jsonPrimitive.contentOrNull?.toDoubleOrNull() ?: continue
                val key = normalize(name)
                if (key.isNotEmpty()) byName.putIfAbsent(key, lat to lon)
            }
        }
    }
}

/**
 * APCA Places (national parks) — centroid-mode arcgis json, features with
 * `attributes.DESC_EN` and `centroid: { x, y }`. Names are like
 * "Banff National Park of Canada"; aggressive normalization in `normalize`
 * collapses the federal-park cruft so they can match the leaf's bare name.
 */
class ApcaPlacesCentroidSource(
    private val envelopes: List<ca.floo.roadtrip.models.metadata.Envelope>,
) : GeometrySource {
    override fun indexInto(byName: MutableMap<String, Pair<Double, Double>>) {
        for (env in envelopes) {
            val feats = env.payload.jsonObject["features"]?.jsonArray ?: continue
            for (f in feats) {
                val o = f.jsonObject
                val attrs = o["attributes"]?.jsonObject ?: continue
                val name = attrs["DESC_EN"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                val centroid = o["centroid"]?.jsonObject ?: continue
                val lon = centroid["x"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                val lat = centroid["y"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                val key = normalize(name)
                if (key.isNotEmpty()) byName.putIfAbsent(key, lat to lon)
            }
        }
    }
}

/** Generic GeoJSON FeatureCollection with `properties.name` (best-effort fallback). */
class GeoJsonFeaturesSource(
    private val envelopes: List<ca.floo.roadtrip.models.metadata.Envelope>,
    private val slug: String,
) : GeometrySource {
    override fun indexInto(byName: MutableMap<String, Pair<Double, Double>>) {
        for (env in envelopes) {
            val feats = env.payload.jsonObject["features"]?.jsonArray ?: continue
            for (f in feats) {
                val o = f.jsonObject
                val props = o["properties"]?.jsonObject ?: continue
                val name =
                    listOfNotNull(
                        props["name"]?.jsonPrimitive?.contentOrNull,
                        props["Name"]?.jsonPrimitive?.contentOrNull,
                    ).firstOrNull { it.isNotBlank() } ?: continue
                val geom = o["geometry"]?.jsonObject ?: continue
                if (geom["type"]?.jsonPrimitive?.contentOrNull != "Point") continue
                val coords = geom["coordinates"]?.jsonArray ?: continue
                if (coords.size < 2) continue
                val lon = coords[0].jsonPrimitive.contentOrNull?.toDoubleOrNull() ?: continue
                val lat = coords[1].jsonPrimitive.contentOrNull?.toDoubleOrNull() ?: continue
                val key = normalize(name)
                if (key.isNotEmpty()) byName.putIfAbsent(key, lat to lon)
            }
        }
    }
}

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

private fun csvSplit(line: String): List<String> {
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
