package ca.floo.roadtrip.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CampgroundSearchConfigTest {
    @Test
    fun `defaults bound the search and the bulk read`() {
        assertEquals(200, CampgroundSearchConfig.default.maxResults)
        assertEquals(50, CampgroundSearchConfig.default.maxDetailIds)
    }

    @Test
    fun `caps below one are refused`() {
        assertFailsWith<IllegalArgumentException> { CampgroundSearchConfig(maxResults = 0, maxDetailIds = 50) }
        assertFailsWith<IllegalArgumentException> { CampgroundSearchConfig(maxResults = 200, maxDetailIds = 0) }
    }
}
