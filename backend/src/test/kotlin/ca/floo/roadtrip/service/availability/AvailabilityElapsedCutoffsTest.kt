package ca.floo.roadtrip.service.availability

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which day counts as "already elapsed" is a per-campground question.
 *
 * Marking a cell `past` used to key off one UTC `today` for the whole run, so a
 * Pacific campground's rows flipped at 17:00 local (16:00 in winter) — while the
 * date window, and everything else that decides a date, resolves in the
 * campground's own zone. The gap is not cosmetic: the cube serves the flipped
 * row to the client, and the next poll sees `past` -> `available`, emits a
 * transition, and fires a watch alert for an opening that never happened.
 */
class AvailabilityElapsedCutoffsTest {
    /** 2026-07-05T04:30Z — already the 5th in UTC, still the 4th in Los Angeles. */
    private val clock = Clock.fixed(Instant.parse("2026-07-05T04:30:00Z"), ZoneOffset.UTC)

    private val losAngeles = ZoneId.of("America/Los_Angeles")
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun `each zone gets its own local day, not one UTC day`() {
        val cutoffs =
            elapsedCutoffs(
                listOf(losAngeles to listOf(1L, 2L), newYork to listOf(3L)),
                clock,
            )

        // 04:30Z is 21:30 on the 4th in LA, and 00:30 on the 5th in NY.
        assertEquals(
            mapOf(
                LocalDate.of(2026, 7, 4) to listOf(1L, 2L),
                LocalDate.of(2026, 7, 5) to listOf(3L),
            ),
            cutoffs,
        )
    }

    @Test
    fun `campsites sharing a local day are collapsed into one cutoff`() {
        // Two groups, same zone: one repo call, not two.
        val cutoffs = elapsedCutoffs(listOf(newYork to listOf(3L), newYork to listOf(4L)), clock)

        assertEquals(mapOf(LocalDate.of(2026, 7, 5) to listOf(3L, 4L)), cutoffs)
    }

    @Test
    fun `a campsite seen twice is not marked twice`() {
        val cutoffs = elapsedCutoffs(listOf(newYork to listOf(3L, 4L), newYork to listOf(4L)), clock)

        assertEquals(mapOf(LocalDate.of(2026, 7, 5) to listOf(3L, 4L)), cutoffs)
    }

    @Test
    fun `zones that agree on the date share one cutoff entry`() {
        // Same instant, same local date — grouping is by resolved date, not by
        // zone, so this must not issue two identical repo calls.
        val cutoffs =
            elapsedCutoffs(
                listOf(newYork to listOf(3L), ZoneId.of("America/Toronto") to listOf(5L)),
                clock,
            )

        assertEquals(mapOf(LocalDate.of(2026, 7, 5) to listOf(3L, 5L)), cutoffs)
    }

    @Test
    fun `no campsites means no cutoffs`() {
        assertEquals(emptyMap(), elapsedCutoffs(listOf(losAngeles to emptyList()), clock))
    }
}
