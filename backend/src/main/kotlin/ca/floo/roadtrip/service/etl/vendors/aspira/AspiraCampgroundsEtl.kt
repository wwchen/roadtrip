package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.domain.CampgroundLink
import ca.floo.roadtrip.model.domain.CampgroundLocation
import ca.floo.roadtrip.model.domain.CampgroundManagement
import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.domain.GeometryProvenance
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.TransformResult
import ca.floo.roadtrip.model.metadata.registry.GeometryPolicy
import ca.floo.roadtrip.service.etl.framework.CampgroundEtl
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
// One ETL class. Each row's `geometry:` block declares which of its inputs
// carry coordinates, in which preference order, and how names are matched;
// this class reads only that declaration.
//
// Match strategy: aggressive name normalization (lowercase, drop park /
// campground / national-park-of-canada / etc. suffixes), then exact
// match against the union name → coords index; fallback to a Jaccard token
// overlap at `match.fuzzy_threshold`; final fallback, when
// `match.parent_fallback` is set, to the leaf's `parent_name`.
// Leaves that can't be matched are dropped — the booking ID alone
// doesn't earn a pin on the map.
class AspiraCampgroundsEtl(
    override val etlSlug: String,
    private val aspiraTenant: String,
    private val geometry: GeometryPolicy,
) : CampgroundEtl<AspiraJoinDto> {
    private val log = LoggerFactory.getLogger(javaClass)
    override val multiPart: Boolean = true

    /** The declared geometry inputs, paired with the parser each format names, in preference order. */
    internal fun geometrySourcesFor(inputs: InputBundle): List<Pair<String, GeometrySource>> =
        geometry.sources.map { spec -> spec.input to GeometrySources.forSpec(spec, inputs.envelopes(spec.input)) }

    override fun parse(inputs: InputBundle): Sequence<ParseResult<AspiraJoinDto>> =
        sequence {
            val slugs = inputs.dataSourceSlugs()
            val declared = geometry.sources.map { it.input }.toSet()
            val roleSlugs = slugs.filterNot { it in declared }
            val mapsSlug = roleSlugs.firstOrNull { it.contains(AspiraInputRoles.MAPS) }
            val inventorySlug = roleSlugs.firstOrNull { it.contains(AspiraInputRoles.INVENTORY) }
            val dictionarySlug = roleSlugs.firstOrNull { it.contains(AspiraInputRoles.DICTIONARIES) }

            val errs = mutableListOf<String>()
            for (spec in geometry.sources) {
                when {
                    spec.input !in slugs -> errs += "declared geometry source '${spec.input}' is not among this run's inputs"
                    inputs.envelopes(spec.input).isEmpty() -> errs += "declared geometry source '${spec.input}' has no envelopes"
                }
            }
            val accounted = declared + setOfNotNull(mapsSlug, inventorySlug, dictionarySlug)
            for (slug in slugs - accounted) {
                errs += "input '$slug' is neither a declared geometry source nor the maps, inventory or dictionaries feed"
            }
            if (mapsSlug == null) errs += "no /api/maps input declared"

            if (errs.isNotEmpty()) {
                yield(ParseResult.Bad(null, errs))
                return@sequence
            }

            val leaves = AspiraLeavesWalk.walk(inputs.envelope(checkNotNull(mapsSlug)).payload.jsonArray)
            val dto =
                AspiraJoinDto(
                    leaves = leaves,
                    geomSources = geometrySourcesFor(inputs),
                    inventoryEnvelopes = inventorySlug?.let { inputs.envelopes(it) } ?: emptyList(),
                    dictionaryPayload = dictionarySlug?.let { inputs.envelope(it).payload as? JsonObject },
                    fetchedAt = Instant.now(),
                )
            if (dto.leaves.isEmpty()) {
                yield(ParseResult.Bad(null, listOf("no leaves from /api/maps")))
            } else {
                yield(ParseResult.Ok(dto))
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

        val matcher =
            AspiraLeafMatcher(
                byName = GeometryIndex.build(dto.geomSources, log, etlSlug),
                nonBookableResourceLocationIds = nonBookableResLocs,
                policy = geometry.match,
            )
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

    private fun campgroundCandidate(
        match: AspiraLeafMatch<GeometryPoint>,
        host: String,
        subcategory: String?,
        agency: String,
        bookableMapIds: Map<Long, Set<Long>>,
    ): CampgroundUpsertCandidate {
        val leaf = match.leaf
        val point = match.value
        val dataRef = DataProviderRef.Aspira(transactionLocationId = leaf.transactionLocationId, mapId = leaf.mapId)
        val bookingCtaRef = AspiraBookingCtaRefs.forLeaf(leaf, bookableMapIds)
        return CampgroundUpsertCandidate(
            dataProviderRef = dataRef,
            bookingProvider = BookingProvider.ASPIRA,
            bookingProviderRef = bookingCtaRef?.let { campgroundBookingProviderRef(leaf, it) },
            name = leaf.name,
            parentName = leaf.parentName,
            latitude = point.latitude,
            longitude = point.longitude,
            kind = subcategory,
            location = CampgroundLocation(latitude = point.latitude, longitude = point.longitude),
            reservationUrl = "https://$host/",
            links = listOf(CampgroundLink("https://$host/")),
            management = CampgroundManagement(agency),
            sourceUrl = "https://$host/",
            sourcePayload = aspiraSourcePayload(leaf),
            geometryProvenance =
                GeometryProvenance(
                    matchKind = match.kind.label,
                    source = point.source,
                    matchedName = match.matchedName,
                    score = match.score,
                ),
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

    private fun aspiraSourcePayload(leaf: AspiraLeaf): JsonObject =
        buildJsonObject {
            put("name", leaf.name)
            put(ASPIRA_TRANSACTION_LOCATION_ID_KEY, leaf.transactionLocationId)
            put(ASPIRA_MAP_ID_KEY, leaf.mapId)
            leaf.resourceLocationId?.let { put(ASPIRA_RESOURCE_LOCATION_ID_KEY, it) }
            leaf.parentName?.let { put("parent_name", it) }
        }

    companion object {
        private const val ASPIRA_TRANSACTION_LOCATION_ID_KEY = "transactionLocationId"
        private const val ASPIRA_MAP_ID_KEY = "mapId"
        private const val ASPIRA_RESOURCE_LOCATION_ID_KEY = "resourceLocationId"
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
