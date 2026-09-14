package ca.floo.roadtrip.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CampgroundSearchConfigTest {
    @Test
    fun `defaults bound the search and the bulk read`() {
        assertEquals(200, CampgroundSearchConfig.default.maxResults)
        assertEquals(50, CampgroundSearchConfig.default.maxDetailIds)
        assertEquals(500.0, CampgroundSearchConfig.default.maxBoundaryAreaSqDeg)
    }

    @Test
    fun `caps below one are refused`() {
        assertFailsWith<IllegalArgumentException> {
            CampgroundSearchConfig(maxResults = 0, maxDetailIds = 50, maxBoundaryAreaSqDeg = 500.0)
        }
        assertFailsWith<IllegalArgumentException> {
            CampgroundSearchConfig(maxResults = 200, maxDetailIds = 0, maxBoundaryAreaSqDeg = 500.0)
        }
    }

    @Test
    fun `a zero or negative boundary area cap is refused`() {
        assertFailsWith<IllegalArgumentException> {
            CampgroundSearchConfig(maxResults = 200, maxDetailIds = 50, maxBoundaryAreaSqDeg = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            CampgroundSearchConfig(maxResults = 200, maxDetailIds = 50, maxBoundaryAreaSqDeg = -1.0)
        }
    }
}
