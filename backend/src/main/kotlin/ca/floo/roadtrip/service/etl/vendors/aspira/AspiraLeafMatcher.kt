package ca.floo.roadtrip.service.etl.vendors.aspira

// Misses named in the summary log; enough to start a diagnosis without
// turning one line into a dump.
private const val LOGGED_MISS_SAMPLES = 5

// Minimum Jaccard token overlap for a fuzzy name match.
private const val FUZZY_THRESHOLD = 0.5

/**
 * Resolves Aspira `/api/maps` leaves to geometry through a normalized-name
 * index (see [normalize]): exact name first, then Jaccard token overlap,
 * then the parent park's name.
 *
 * [matchBookable] also owns the two gates every Aspira tenant applies before
 * lookup. Leaves without a resourceLocationId are park containers (Banff,
 * Jasper, …), not bookable resources; emitting them layered a duplicate park
 * pin over the park's own campground pins, and the parent-name fallback keeps
 * every park represented through its campgrounds. Leaves whose
 * resourceLocationId holds only non-bookable inventory are activity mounts
 * (parking, shuttles), not campgrounds, even when their name matches.
 */
class AspiraLeafMatcher<T : Any>(
    private val byName: Map<String, T>,
    private val nonBookableResourceLocationIds: Set<Long>,
) {
    private val tokenIndex: List<Pair<Set<String>, T>> =
        byName.entries.map { (key, value) -> key.split(' ').toSet() to value }

    /** Per-run counts of how leaves were matched or skipped, for the summary log. */
    data class Tally(
        val exact: Int,
        val fuzzy: Int,
        val parent: Int,
        val miss: Int,
        val skippedContainer: Int,
        val skippedNonBookable: Int,
        val missSamples: List<String>,
    )

    data class Result<V>(
        val matches: List<AspiraLeafMatch<V>>,
        val tally: Tally,
    )

    fun matchBookable(leaves: List<AspiraLeaf>): Result<T> {
        val matches = mutableListOf<AspiraLeafMatch<T>>()
        val countByKind = mutableMapOf<AspiraLeafMatchKind, Int>()
        var miss = 0
        var skippedContainer = 0
        var skippedNonBookable = 0
        val missSamples = mutableListOf<String>()

        for (leaf in leaves) {
            val resourceLocationId = leaf.resourceLocationId
            if (resourceLocationId == null) {
                skippedContainer++
                continue
            }
            if (resourceLocationId in nonBookableResourceLocationIds) {
                skippedNonBookable++
                continue
            }
            val match = match(leaf)
            if (match == null) {
                miss++
                if (missSamples.size < LOGGED_MISS_SAMPLES) missSamples += leaf.name
                continue
            }
            countByKind[match.kind] = (countByKind[match.kind] ?: 0) + 1
            matches += match
        }

        return Result(
            matches = matches,
            tally =
                Tally(
                    exact = countByKind[AspiraLeafMatchKind.EXACT] ?: 0,
                    fuzzy = countByKind[AspiraLeafMatchKind.FUZZY] ?: 0,
                    parent = countByKind[AspiraLeafMatchKind.PARENT] ?: 0,
                    miss = miss,
                    skippedContainer = skippedContainer,
                    skippedNonBookable = skippedNonBookable,
                    missSamples = missSamples,
                ),
        )
    }

    fun match(leaf: AspiraLeaf): AspiraLeafMatch<T>? {
        val key = normalize(leaf.name)
        byName[key]?.let { return AspiraLeafMatch(leaf, it, AspiraLeafMatchKind.EXACT) }

        val tokens = key.split(' ').toSet()
        val best = tokenIndex.maxByOrNull { jaccard(it.first, tokens) }
        if (best != null && jaccard(best.first, tokens) >= FUZZY_THRESHOLD) {
            return AspiraLeafMatch(leaf, best.second, AspiraLeafMatchKind.FUZZY)
        }

        val parentKey = leaf.parentName?.let(::normalize) ?: return null
        return byName[parentKey]?.let { AspiraLeafMatch(leaf, it, AspiraLeafMatchKind.PARENT) }
    }
}
