package ca.floo.roadtrip.service.catalog

import ca.floo.roadtrip.repo.CatalogMatchRepo
import ca.floo.roadtrip.repo.CatalogMatchRepo.MatchPair
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Runs the deterministic + heuristic catalog matching passes and refreshes
 * `match_group_id` on `campgrounds` / `campsites`. Deterministic ordering matters:
 * shared-vendor-ref pass first, then group recompute, then geo+name (which reads
 * `match_group_id`), then group recompute again, then the campsite name pass,
 * then one final recompute.
 */
class CatalogMatcherService(
    private val matches: CatalogMatchRepo,
    private val config: MatcherConfig,
) {
    data class MatcherConfig(
        val maxDistanceM: Double,
        val minNameSimilarity: Double,
    ) {
        companion object {
            fun fromEnv(env: Map<String, String> = System.getenv()): MatcherConfig =
                MatcherConfig(
                    maxDistanceM =
                        env[ENV_MAX_DISTANCE_M]?.toDoubleOrNull() ?: DEFAULT_MAX_DISTANCE_M,
                    minNameSimilarity =
                        env[ENV_MIN_NAME_SIMILARITY]?.toDoubleOrNull() ?: DEFAULT_MIN_NAME_SIMILARITY,
                )
        }
    }

    data class MatchRunStats(
        val campgroundPairs: Int,
        val campsitePairs: Int,
        val groupsRecomputed: Int,
    )

    fun run(): MatchRunStats {
        // 1) Deterministic pass: shared vendor refs.
        val sharedPairs = matches.sharedVendorRefCampgroundPairs()
        matches.upsertCampgroundMatches(sharedPairs)

        // 2) Recompute groups so the geo+name pass reads coherent match groups.
        matches.recomputeMatchGroups()

        // 3) Heuristic pass: near + similar-name across etl_sources.
        val geoNamePairs =
            matches
                .geoNameCampgroundCandidates(config.maxDistanceM)
                .mapNotNull { candidate ->
                    val similarity = nameSimilarity(candidate.aName, candidate.bName)
                    if (similarity < config.minNameSimilarity) return@mapNotNull null
                    MatchPair(
                        aId = candidate.aId,
                        bId = candidate.bId,
                        heuristic = geoNameHeuristic(similarity = similarity, distanceM = candidate.distanceM),
                    )
                }
        matches.upsertCampgroundMatches(geoNamePairs)

        // 4) Recompute so the campsite pass keys on freshly-merged campground groups.
        matches.recomputeMatchGroups()

        // 5) Campsite pass: exact normalized (loop, name) within shared campground groups.
        val campsitePairs =
            matches
                .campsiteNameCandidates()
                .mapNotNull { candidate ->
                    if (!equalNormalizedNames(candidate.aLoop, candidate.bLoop)) return@mapNotNull null
                    if (!equalNormalizedNames(candidate.aName, candidate.bName)) return@mapNotNull null
                    MatchPair(
                        aId = candidate.aId,
                        bId = candidate.bId,
                        heuristic = campsiteExactHeuristic(),
                    )
                }
        matches.upsertCampsiteMatches(campsitePairs)

        // 6) Final recompute across both tables. The rows-updated count from
        //    this final pass is what we surface — earlier iterations converged
        //    to their intermediate state.
        val groupsRecomputed = matches.recomputeMatchGroups()

        return MatchRunStats(
            campgroundPairs = sharedPairs.size + geoNamePairs.size,
            campsitePairs = campsitePairs.size,
            groupsRecomputed = groupsRecomputed,
        )
    }

    /**
     * Normalized-token Jaccard: lowercase, drop non-alphanumeric/whitespace,
     * split on whitespace, drop empty tokens. Returns 0.0 when both sets are
     * empty so an empty-vs-empty pair never satisfies the threshold.
     */
    private fun nameSimilarity(
        a: String,
        b: String,
    ): Double {
        val setA = tokenize(a)
        val setB = tokenize(b)
        if (setA.isEmpty() && setB.isEmpty()) return 0.0
        val intersection = setA.intersect(setB).size.toDouble()
        val union = setA.union(setB).size.toDouble()
        if (union == 0.0) return 0.0
        return intersection / union
    }

    private fun tokenize(value: String): Set<String> =
        value
            .lowercase()
            .map { ch -> if (ch.isLetterOrDigit() || ch.isWhitespace()) ch else ' ' }
            .joinToString("")
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Exact-equal after trim + lowercase. Nulls and blanks are equivalent —
     * a missing loop_name on one side matches a missing loop_name on the other,
     * but neither matches a real value.
     */
    private fun equalNormalizedNames(
        a: String?,
        b: String?,
    ): Boolean {
        val na = a?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val nb = b?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return na == nb
    }

    private fun geoNameHeuristic(
        similarity: Double,
        distanceM: Double,
    ): JsonObject =
        buildJsonObject {
            put("method", JsonPrimitive(METHOD_GEO_NAME))
            put("score", JsonPrimitive(similarity))
            put("distance_m", JsonPrimitive(distanceM))
            put("name_similarity", JsonPrimitive(similarity))
        }

    private fun campsiteExactHeuristic(): JsonObject =
        buildJsonObject {
            put("method", JsonPrimitive(METHOD_GEO_NAME))
            put("score", JsonPrimitive(1.0))
            put("matched_on", JsonPrimitive(CAMPSITE_MATCHED_ON_LOOP_NAME))
        }

    companion object {
        const val DEFAULT_MAX_DISTANCE_M: Double = 500.0
        const val DEFAULT_MIN_NAME_SIMILARITY: Double = 0.85
        const val METHOD_SHARED_VENDOR_REF: String = "shared_vendor_ref"
        const val METHOD_GEO_NAME: String = "geo_name"
        const val METHOD_MANUAL: String = "manual"

        const val ENV_MAX_DISTANCE_M: String = "MATCH_MAX_DISTANCE_M"
        const val ENV_MIN_NAME_SIMILARITY: String = "MATCH_MIN_NAME_SIMILARITY"

        private const val CAMPSITE_MATCHED_ON_LOOP_NAME: String = "loop+name"
        private val WHITESPACE = Regex("""\s+""")
    }
}
