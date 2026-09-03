package ca.floo.roadtrip.service.etl.vendors.aspira

import org.slf4j.LoggerFactory
import java.util.EnumMap

// How many unmatched leaf names the summary log names; enough to start a
// diagnosis without turning one line into a dump.
private const val LOGGED_MISS_SAMPLES = 5

/**
 * Joins Aspira `/api/maps` leaves to a name-keyed geometry index.
 *
 * `/api/maps` carries booking IDs but no lat/lng, so every Aspira-backed
 * campground ETL has to pair each leaf with a sibling source that does. The
 * pairing is the same regardless of what that source is: skip park containers
 * (no `resourceLocationId`), skip non-bookable activity mounts, then walk the
 * ladder exact name → Jaccard token overlap ≥ [FUZZY_THRESHOLD] → the leaf's
 * `parent_name`. Leaves that reach nothing are dropped — a booking id alone
 * does not earn a pin on the map.
 *
 * [G] is whatever the index holds against a normalized name: bare
 * coordinates for [AspiraCampgroundsEtl], a whole Strapi row for
 * `BcParksCampgroundsEtl`. The matcher never looks inside it.
 *
 * @param byName normalized name → geometry, in the caller's preference order.
 * @param nonBookableResourceLocationIds leaves whose inventory holds no
 *   overnight-stay resource; see [AspiraInventoryCategories].
 * @param parentNameFallback whether the last rung of the ladder runs. Opt-in
 *   because a parent-park centroid is the right pin for a tenant built as
 *   park containers holding campgrounds (Parks Canada) and an unwanted guess
 *   for one that is not.
 */
class AspiraGeometryMatcher<G : Any>(
    private val etlSlug: String,
    private val byName: Map<String, G>,
    private val nonBookableResourceLocationIds: Set<Long>,
    private val parentNameFallback: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Token sets for the Jaccard rung, built once per index.
    private val tokenIndex: List<Pair<Set<String>, G>> =
        byName.entries.map { (key, geometry) -> key.split(' ').toSet() to geometry }

    /**
     * Runs every leaf through the ladder, in order, and logs one summary line.
     * The returned matches are the leaves that earned a pin, in input order.
     */
    fun matchAll(leaves: List<AspiraLeaf>): MatchOutcome<G> {
        val tally = MatchTally()
        val matches = leaves.mapNotNull { leaf -> match(leaf, tally) }
        log.info(
            "$etlSlug: {} leaves → {} campgrounds " +
                "(exact={} fuzzy={} parent={} miss={} skippedContainer={} skippedNonBookable={}; sample misses: {})",
            leaves.size,
            matches.size,
            tally.count(MatchKind.EXACT),
            tally.count(MatchKind.FUZZY),
            tally.count(MatchKind.PARENT),
            tally.miss,
            tally.skippedContainer,
            tally.skippedNonBookable,
            tally.missSamples,
        )
        return MatchOutcome(matches, tally)
    }

    private fun match(
        leaf: AspiraLeaf,
        tally: MatchTally,
    ): LeafMatch<G>? {
        // A leaf with no resourceLocationId is a park container or activity
        // mount, not a bookable resource location: its child campgrounds carry
        // the coordinates, so emitting it would layer a duplicate park pin.
        if (leaf.resourceLocationId == null) {
            tally.skipContainer()
            return null
        }
        if (leaf.resourceLocationId in nonBookableResourceLocationIds) {
            tally.skipNonBookable()
            return null
        }
        val key = normalize(leaf.name)
        val found = exact(leaf, key) ?: fuzzy(leaf, key) ?: viaParent(leaf)
        if (found == null) {
            tally.missed(leaf.name)
            return null
        }
        tally.matched(found.kind)
        return found
    }

    private fun exact(
        leaf: AspiraLeaf,
        key: String,
    ): LeafMatch<G>? = byName[key]?.let { LeafMatch(leaf, it, MatchKind.EXACT) }

    private fun fuzzy(
        leaf: AspiraLeaf,
        key: String,
    ): LeafMatch<G>? {
        val tokens = key.split(' ').toSet()
        val (best, score) =
            tokenIndex
                .map { (indexTokens, geometry) -> geometry to jaccard(indexTokens, tokens) }
                .maxByOrNull { (_, score) -> score }
                ?: return null
        return if (score >= FUZZY_THRESHOLD) LeafMatch(leaf, best, MatchKind.FUZZY) else null
    }

    private fun viaParent(leaf: AspiraLeaf): LeafMatch<G>? {
        if (!parentNameFallback || leaf.parentName == null) return null
        return byName[normalize(leaf.parentName)]?.let { LeafMatch(leaf, it, MatchKind.PARENT) }
    }

    companion object {
        /** Minimum Jaccard overlap between a leaf's tokens and an index key's for the fuzzy rung. */
        const val FUZZY_THRESHOLD = 0.5
    }
}

/** A leaf that reached geometry, and which rung of the ladder got it there. */
data class LeafMatch<G>(
    val leaf: AspiraLeaf,
    val geometry: G,
    val kind: MatchKind,
)

data class MatchOutcome<G>(
    val matches: List<LeafMatch<G>>,
    val tally: MatchTally,
)

/** Where every leaf of one [AspiraGeometryMatcher.matchAll] ended up. */
class MatchTally {
    private val matched = EnumMap<MatchKind, Int>(MatchKind::class.java)
    private val sampledMisses = mutableListOf<String>()

    var miss: Int = 0
        private set
    var skippedContainer: Int = 0
        private set
    var skippedNonBookable: Int = 0
        private set

    /** The first few unmatched leaf names, for the summary log. */
    val missSamples: List<String> get() = sampledMisses

    fun count(kind: MatchKind): Int = matched[kind] ?: 0

    internal fun matched(kind: MatchKind) {
        matched.merge(kind, 1, Int::plus)
    }

    internal fun missed(name: String) {
        miss++
        if (sampledMisses.size < LOGGED_MISS_SAMPLES) sampledMisses += name
    }

    internal fun skipContainer() {
        skippedContainer++
    }

    internal fun skipNonBookable() {
        skippedNonBookable++
    }
}
