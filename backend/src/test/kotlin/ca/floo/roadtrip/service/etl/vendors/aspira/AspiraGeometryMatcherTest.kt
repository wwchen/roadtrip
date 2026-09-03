package ca.floo.roadtrip.service.etl.vendors.aspira

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ladder and its bookkeeping in isolation from either ETL: which rung
 * a leaf lands on, in which order the rungs are tried, and what the tally
 * says afterwards. Index keys are already normalized, as the callers' are.
 */
class AspiraGeometryMatcherTest {
    private val banff = 51.18 to -115.57
    private val twoJack = 51.23 to -115.50

    private val index: Map<String, Pair<Double, Double>> =
        linkedMapOf(
            "banff" to banff,
            "two jack lakeside" to twoJack,
        )

    // Shares 3 of 3 tokens with "two jack lakeside" and none with the parent.
    private val exactLeaf = leaf("Two Jack Lakeside Campground", parentName = "Banff National Park of Canada")

    // {two, jack, lakeside, overflow} vs {two, jack, lakeside}: 3/4 = 0.75.
    private val fuzzyLeaf = leaf("Two Jack Lakeside Overflow", parentName = "Banff National Park of Canada")

    // No token in common with either key; only the parent can place it.
    private val parentOnlyLeaf = leaf("Backcountry Reservations", parentName = "Banff National Park of Canada")

    private val orphanLeaf = leaf("Backcountry Reservations", parentName = "Nowhere In The Index")

    private fun matcher(
        parentNameFallback: Boolean = true,
        nonBookable: Set<Long> = emptySet(),
    ) = AspiraGeometryMatcher(
        etlSlug = "test-campgrounds",
        byName = index,
        nonBookableResourceLocationIds = nonBookable,
        parentNameFallback = parentNameFallback,
    )

    private fun leaf(
        name: String,
        resourceLocationId: Long? = 1L,
        parentName: String? = null,
    ) = AspiraLeaf(
        name = name,
        transactionLocationId = 10L,
        mapId = 20L,
        resourceLocationId = resourceLocationId,
        parentName = parentName,
    )

    @Test
    fun `an exact name match is taken before the fuzzy and parent rungs`() {
        val match = matcher().matchAll(listOf(exactLeaf)).matches.single()

        assertEquals(MatchKind.EXACT, match.kind)
        assertEquals(twoJack, match.geometry)
    }

    @Test
    fun `a fuzzy match is taken before the parent rung`() {
        val match = matcher().matchAll(listOf(fuzzyLeaf)).matches.single()

        assertEquals(MatchKind.FUZZY, match.kind)
        assertEquals(twoJack, match.geometry, "the overlapping key wins, not the parent's")
    }

    @Test
    fun `the fuzzy threshold is inclusive`() {
        // {two, jack, lakeside, alpha, beta, gamma} vs {two, jack, lakeside}: 3/6 = 0.5 exactly.
        val atThreshold = leaf("Two Jack Lakeside Alpha Beta Gamma")
        // One more token: 3/7, just under.
        val underThreshold = leaf("Two Jack Lakeside Alpha Beta Gamma Delta")

        val outcome = matcher().matchAll(listOf(atThreshold, underThreshold))

        assertEquals(listOf(MatchKind.FUZZY), outcome.matches.map { it.kind })
        assertEquals(atThreshold, outcome.matches.single().leaf)
        assertEquals(1, outcome.tally.miss)
    }

    @Test
    fun `the parent rung places a leaf neither of the name rungs could`() {
        val match = matcher().matchAll(listOf(parentOnlyLeaf)).matches.single()

        assertEquals(MatchKind.PARENT, match.kind)
        assertEquals(banff, match.geometry)
    }

    @Test
    fun `the parent rung is skipped when the fallback is off`() {
        val outcome = matcher(parentNameFallback = false).matchAll(listOf(parentOnlyLeaf, exactLeaf))

        assertEquals(listOf(MatchKind.EXACT), outcome.matches.map { it.kind })
        assertEquals(0, outcome.tally.count(MatchKind.PARENT))
        assertEquals(1, outcome.tally.miss)
    }

    @Test
    fun `an unknown parent does not rescue a leaf`() {
        val outcome = matcher().matchAll(listOf(orphanLeaf))

        assertTrue(outcome.matches.isEmpty())
        assertEquals(listOf("Backcountry Reservations"), outcome.tally.missSamples)
    }

    @Test
    fun `a park container is skipped even when its name is in the index`() {
        val outcome = matcher().matchAll(listOf(leaf("Banff", resourceLocationId = null)))

        assertTrue(outcome.matches.isEmpty())
        assertEquals(1, outcome.tally.skippedContainer)
        assertEquals(0, outcome.tally.miss, "a skip is not a miss")
    }

    @Test
    fun `a non-bookable leaf is skipped before the ladder runs`() {
        val outcome = matcher(nonBookable = setOf(1L)).matchAll(listOf(exactLeaf))

        assertTrue(outcome.matches.isEmpty())
        assertEquals(1, outcome.tally.skippedNonBookable)
        assertEquals(0, outcome.tally.count(MatchKind.EXACT))
    }

    @Test
    fun `the tally accounts for every leaf and the matches keep input order`() {
        val leaves =
            listOf(
                parentOnlyLeaf,
                leaf("Banff", resourceLocationId = null),
                exactLeaf,
                orphanLeaf,
                leaf("Parking", resourceLocationId = 99L),
                fuzzyLeaf,
            )

        val outcome = matcher(nonBookable = setOf(99L)).matchAll(leaves)

        assertEquals(listOf(parentOnlyLeaf, exactLeaf, fuzzyLeaf), outcome.matches.map { it.leaf })
        with(outcome.tally) {
            assertEquals(1, count(MatchKind.EXACT))
            assertEquals(1, count(MatchKind.FUZZY))
            assertEquals(1, count(MatchKind.PARENT))
            assertEquals(1, miss)
            assertEquals(1, skippedContainer)
            assertEquals(1, skippedNonBookable)
        }
    }

    @Test
    fun `miss samples are capped so the summary log stays one line`() {
        val misses = (1..7).map { leaf("Unplaceable $it") }

        val tally = matcher().matchAll(misses).tally

        assertEquals(7, tally.miss)
        assertEquals((1..5).map { "Unplaceable $it" }, tally.missSamples)
    }

    /**
     * `match_kind` is persisted in every campground's metadata and
     * source_payload; renaming a constant must not change what is written.
     */
    @Test
    fun `wire values are the persisted match_kind strings`() {
        assertEquals(
            mapOf(MatchKind.EXACT to "exact", MatchKind.FUZZY to "fuzzy", MatchKind.PARENT to "parent"),
            MatchKind.entries.associateWith { it.wireValue },
        )
    }
}
