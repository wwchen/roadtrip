package ca.floo.roadtrip.service.availability

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvailabilityFreshnessTest {
    private val now = Instant.parse("2026-06-18T12:00:00Z")

    private val day = LocalDate.of(2026, 6, 20)

    @Test
    fun `required cells are one per campsite per date, deduplicated`() {
        val cells = requiredCells(campsiteIds = listOf(1L, 2L, 1L), dates = listOf(day, day.plusDays(1)))

        assertEquals(
            setOf(
                AvailabilityCell(1L, day),
                AvailabilityCell(1L, day.plusDays(1)),
                AvailabilityCell(2L, day),
                AvailabilityCell(2L, day.plusDays(1)),
            ),
            cells,
        )
    }

    @Test
    fun `a required cell with a fresh stored observation is answered`() {
        val cell = AvailabilityCell(1L, day)

        assertTrue(
            unansweredCells(
                required = setOf(cell),
                observedAtByCell = mapOf(cell to now),
                freshAtOrAfter = now,
            ).isEmpty(),
        )
    }

    @Test
    fun `a required cell with no stored observation is unanswered`() {
        val cell = AvailabilityCell(1L, day)

        assertEquals(
            setOf(cell),
            unansweredCells(required = setOf(cell), observedAtByCell = emptyMap(), freshAtOrAfter = now),
        )
    }

    @Test
    fun `only the stale cells come back unanswered, not the whole batch`() {
        val fresh = AvailabilityCell(1L, day)
        val stale = AvailabilityCell(2L, day)

        assertEquals(
            setOf(stale),
            unansweredCells(
                required = setOf(fresh, stale),
                observedAtByCell = mapOf(fresh to now, stale to now.minusSeconds(1)),
                freshAtOrAfter = now,
            ),
        )
    }

    @Test
    fun `a stored cell nobody asked for cannot answer for a missing one`() {
        val required = AvailabilityCell(1L, day)
        val unrelated = AvailabilityCell(2L, day)

        assertEquals(
            setOf(required),
            unansweredCells(
                required = setOf(required),
                observedAtByCell = mapOf(unrelated to now),
                freshAtOrAfter = now,
            ),
        )
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
