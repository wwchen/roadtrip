package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.registry.MatchPolicy

// Misses named in the summary log; enough to start a diagnosis without
// turning one line into a dump.
private const val LOGGED_MISS_SAMPLES = 5

/**
 * Resolves Aspira `/api/maps` leaves to geometry through a normalized-name
 * index (see [normalize]): exact name first, then Jaccard token overlap at
 * [MatchPolicy.fuzzyThreshold], then — only when [MatchPolicy.parentFallback]
 * is set — the parent park's name.
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
    private val policy: MatchPolicy,
) {
    private val tokenIndex: List<IndexEntry<T>> =
        byName.entries.map { (key, value) -> IndexEntry(key, key.split(' ').toSet(), value) }

    private data class IndexEntry<T>(
        val key: String,
        val tokens: Set<String>,
        val value: T,
    )

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
        byName[key]?.let { return AspiraLeafMatch(leaf, it, AspiraLeafMatchKind.EXACT, matchedName = key) }

        // One score per entry, first maximum kept: ties resolve by index order,
        // which is source preference and then feed row order — the same rule
        // that settles an exact-name collision.
        val tokens = key.split(' ').toSet()
        var best: IndexEntry<T>? = null
        var bestScore = 0.0
        for (entry in tokenIndex) {
            val score = jaccard(entry.tokens, tokens)
            if (best == null || score > bestScore) {
                best = entry
                bestScore = score
            }
        }
        if (best != null && bestScore >= policy.fuzzyThreshold) {
            return AspiraLeafMatch(leaf, best.value, AspiraLeafMatchKind.FUZZY, matchedName = best.key, score = bestScore)
        }

        if (!policy.parentFallback) return null
        val parentKey = leaf.parentName?.let(::normalize) ?: return null
        return byName[parentKey]?.let { AspiraLeafMatch(leaf, it, AspiraLeafMatchKind.PARENT, matchedName = parentKey) }
    }
}
