package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.registry.MatchPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AspiraLeafMatcherTest {
    private val banff = 51.18 to -115.57
    private val twoJack = 51.22 to -115.49

    private fun matcher(
        policy: MatchPolicy = MatchPolicy(parentFallback = true),
        byName: Map<String, Pair<Double, Double>> = mapOf("banff" to banff, "two jack lakeside" to twoJack),
    ) = AspiraLeafMatcher(byName = byName, nonBookableResourceLocationIds = setOf(NON_BOOKABLE), policy = policy)

    @Test
    fun `exact normalized name wins and names the index key it hit`() {
        val match = matcher().match(leaf("Two Jack Lakeside Campground"))

        assertEquals(
            AspiraLeafMatch(
                leaf = leaf("Two Jack Lakeside Campground"),
                value = twoJack,
                kind = AspiraLeafMatchKind.EXACT,
                matchedName = "two jack lakeside",
            ),
            match,
        )
    }

    @Test
    fun `token overlap at the threshold is a fuzzy match carrying its score`() {
        // {two, jack} of {two, jack, lake, lakeside} — exactly the default threshold.
        val match = matcher().match(leaf("Two Jack Lake"))

        assertEquals(AspiraLeafMatchKind.FUZZY, match?.kind)
        assertEquals(twoJack, match?.value)
        assertEquals("two jack lakeside", match?.matchedName)
        assertEquals(MatchPolicy.DEFAULT_FUZZY_THRESHOLD, match?.score)
    }

    @Test
    fun `a threshold above the best overlap rejects the fuzzy match`() {
        assertNull(matcher(policy = MatchPolicy(fuzzyThreshold = STRICT_THRESHOLD, parentFallback = false)).match(leaf("Two Jack Lake")))
    }

    @Test
    fun `parent park name backstops a leaf with no geometry of its own`() {
        val match = matcher().match(leaf("Backcountry Site", parentName = "Banff National Park of Canada"))

        assertEquals(AspiraLeafMatchKind.PARENT, match?.kind)
        assertEquals(banff, match?.value)
        assertEquals("banff", match?.matchedName)
        assertNull(match?.score)
    }

    @Test
    fun `parent fallback off leaves that same leaf unmatched`() {
        val policy = MatchPolicy(parentFallback = false)

        assertNull(matcher(policy).match(leaf("Backcountry Site", parentName = "Banff National Park of Canada")))
    }

    /**
     * Ties resolve the same way exact collisions do: by index order, which is
     * source preference and then feed row order. The index is a LinkedHashMap,
     * so "alpha lake" is entered first and must win.
     */
    @Test
    fun `a tie keeps the first entry in index order`() {
        val byName =
            linkedMapOf(
                "alpha lake" to (1.0 to 2.0),
                "beta lake" to (3.0 to 4.0),
            )

        val match = matcher(byName = byName).match(leaf("Lake"))

        assertEquals("alpha lake", match?.matchedName)
        assertEquals(1.0 to 2.0, match?.value)
    }

    @Test
    fun `a leaf matching neither itself nor its parent is unmatched`() {
        assertNull(matcher().match(leaf("Nowhere Site", parentName = "Elsewhere")))
    }

    @Test
    fun `matchBookable skips containers and non-bookable leaves before lookup`() {
        val leaves =
            listOf(
                leaf("Banff", resourceLocationId = null),
                leaf("Two Jack Lakeside", resourceLocationId = NON_BOOKABLE),
                leaf("Two Jack Lakeside"),
                leaf("Two Jack Lake"),
                leaf("Backcountry Site", parentName = "Banff"),
            ) + (1..7).map { leaf("Miss $it") }
        val (matches, tally) = matcher().matchBookable(leaves)

        assertEquals(listOf("Two Jack Lakeside", "Two Jack Lake", "Backcountry Site"), matches.map { it.leaf.name })
        assertEquals(
            AspiraLeafMatcher.Tally(
                exact = 1,
                fuzzy = 1,
                parent = 1,
                miss = 7,
                skippedContainer = 1,
                skippedNonBookable = 1,
                missSamples = (1..5).map { "Miss $it" },
            ),
            tally,
        )
    }

    private fun leaf(
        name: String,
        resourceLocationId: Long? = BOOKABLE,
        parentName: String? = null,
    ) = AspiraLeaf(
        name = name,
        transactionLocationId = 1L,
        mapId = 2L,
        resourceLocationId = resourceLocationId,
        parentName = parentName,
    )

    private companion object {
        const val BOOKABLE = 9001L
        const val NON_BOOKABLE = 9002L

        /** Above every overlap these fixtures produce, so the fuzzy pass must reject. */
        const val STRICT_THRESHOLD = 0.9
    }
}
