package ca.floo.roadtrip.service.etl.vendors.aspira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AspiraLeafMatcherTest {
    private val banff = 51.18 to -115.57
    private val twoJack = 51.22 to -115.49

    private val matcher =
        AspiraLeafMatcher(
            byName = mapOf("banff" to banff, "two jack lakeside" to twoJack),
            nonBookableResourceLocationIds = setOf(NON_BOOKABLE),
        )

    @Test
    fun `exact normalized name wins`() {
        val match = matcher.match(leaf("Two Jack Lakeside Campground"))
        assertEquals(AspiraLeafMatch(leaf("Two Jack Lakeside Campground"), twoJack, AspiraLeafMatchKind.EXACT), match)
    }

    @Test
    fun `token overlap at the threshold is a fuzzy match`() {
        assertEquals(AspiraLeafMatchKind.FUZZY, matcher.match(leaf("Two Jack Lake"))?.kind)
        assertEquals(twoJack, matcher.match(leaf("Two Jack Lake"))?.value)
    }

    @Test
    fun `parent park name backstops a leaf with no geometry of its own`() {
        val match = matcher.match(leaf("Backcountry Site", parentName = "Banff National Park of Canada"))
        assertEquals(AspiraLeafMatchKind.PARENT, match?.kind)
        assertEquals(banff, match?.value)
    }

    @Test
    fun `a leaf matching neither itself nor its parent is unmatched`() {
        assertNull(matcher.match(leaf("Nowhere Site", parentName = "Elsewhere")))
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
        val (matches, tally) = matcher.matchBookable(leaves)

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
    }
}
