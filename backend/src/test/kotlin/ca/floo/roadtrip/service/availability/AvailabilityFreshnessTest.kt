package ca.floo.roadtrip.service.availability

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvailabilityFreshnessTest {
    private val now = Instant.parse("2026-06-18T12:00:00Z")

    @Test
    fun `coverage requires one row per target per date`() {
        assertTrue(hasFullCoverage(targetCount = 2, dateCount = 3, rowCount = 6))
        assertFalse(hasFullCoverage(targetCount = 2, dateCount = 3, rowCount = 5))
    }

    @Test
    fun `fresh only when every observation is within ttl`() {
        val ttl = Duration.ofMinutes(10)
        assertTrue(isFresh(listOf(now.minusSeconds(60)), now, ttl))
        assertFalse(isFresh(listOf(now.minusSeconds(60), now.minusSeconds(3600)), now, ttl))
        assertEquals(true, isFresh(emptyList(), now, ttl))
    }

    @Test
    fun `isFreshAsOf accepts observations at or after the cutoff`() {
        val cutoff = Instant.parse("2026-08-23T12:00:00Z")
        assertTrue(isFreshAsOf(listOf(cutoff, cutoff.plusSeconds(1)), cutoff))
    }

    @Test
    fun `isFreshAsOf rejects any observation before the cutoff`() {
        val cutoff = Instant.parse("2026-08-23T12:00:00Z")
        assertFalse(isFreshAsOf(listOf(cutoff, cutoff.minusSeconds(1)), cutoff))
    }

    @Test
    fun `isFreshAsOf treats an empty list as fresh`() {
        assertTrue(isFreshAsOf(emptyList(), Instant.parse("2026-08-23T12:00:00Z")))
    }
}
