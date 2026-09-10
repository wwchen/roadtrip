package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.api.AvailabilityCellDto
import ca.floo.roadtrip.model.api.AvailabilityWindowState
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilitySeasonBlock
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.domain.Campsite
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val windowStart: LocalDate = LocalDate.parse("2026-08-10")
private val observedAt: Instant = Instant.parse("2026-08-09T00:00:00Z")
private val freshCache = AvailabilityCacheBlock(hit = true, ageSeconds = 60, ttlSeconds = 600)
private const val ONE_DAY = 1L

/** Every campsite polls, and the whole window is bookable. */
private val everythingPolls: (Campsite) -> Boolean = { true }

class PoiAvailabilityFusionTest {
    // --- rollup, ported one-for-one from frontend fuse.test.ts ---

    @Test
    fun `one bookable site makes the day bookable`() {
        assertEquals(
            AvailabilityStatus.AVAILABLE,
            fusedDay(AvailabilityStatus.RESERVED, AvailabilityStatus.CLOSED, AvailabilityStatus.AVAILABLE).status,
        )
    }

    @Test
    fun `first-come outranks everything except available`() {
        assertEquals(
            AvailabilityStatus.FIRST_COME,
            fusedDay(AvailabilityStatus.RESERVED, AvailabilityStatus.FIRST_COME, AvailabilityStatus.UNKNOWN).status,
        )
    }

    @Test
    fun `unknown outranks reserved`() {
        assertEquals(
            AvailabilityStatus.UNKNOWN,
            fusedDay(AvailabilityStatus.RESERVED, AvailabilityStatus.UNKNOWN).status,
        )
    }

    @Test
    fun `reserved wins only when every other site is closed`() {
        assertEquals(
            AvailabilityStatus.RESERVED,
            fusedDay(AvailabilityStatus.CLOSED, AvailabilityStatus.RESERVED, AvailabilityStatus.CLOSED).status,
        )
    }

    @Test
    fun `closed needs every site to be closed`() {
        assertEquals(
            AvailabilityStatus.CLOSED,
            fusedDay(AvailabilityStatus.CLOSED, AvailabilityStatus.CLOSED).status,
        )
        assertEquals(
            AvailabilityStatus.RESERVED,
            fusedDay(AvailabilityStatus.CLOSED, AvailabilityStatus.RESERVED).status,
        )
    }

    @Test
    fun `a campsite nobody observed is unknown, not closed`() {
        val fused = fuse(sliceOf(mapOf(7L to emptyMap())), everythingPolls, windowStart)

        assertEquals(AvailabilityStatus.UNKNOWN, fused.days.single().status)
        assertEquals(
            mapOf(7L to AvailabilityCellDto(AvailabilityStatus.UNKNOWN, watchable = false)),
            fused.days.single().cells,
        )
    }

    @Test
    fun `an unrecognised upstream status parses to unknown`() {
        assertEquals(AvailabilityStatus.AVAILABLE, AvailabilityStatus.parse("Available"))
        assertEquals(AvailabilityStatus.UNKNOWN, AvailabilityStatus.parse("gibberish"))
    }

    // --- cells, ported from the frontend's fuseDay cases ---

    @Test
    fun `collects each campsite status for the date`() {
        val fused =
            fuse(
                sliceOf(
                    mapOf(
                        7L to mapOf(windowStart to AvailabilityStatus.RESERVED),
                        3L to mapOf(windowStart to AvailabilityStatus.AVAILABLE),
                    ),
                ),
                everythingPolls,
                windowStart,
            )

        val day = fused.days.single()
        assertEquals(
            mapOf(
                3L to AvailabilityCellDto(AvailabilityStatus.AVAILABLE, watchable = false),
                7L to AvailabilityCellDto(AvailabilityStatus.RESERVED, watchable = true),
            ),
            day.cells,
        )
        assertEquals(AvailabilityStatus.AVAILABLE, day.status)
    }

    @Test
    fun `orders campsites numerically`() {
        val available = mapOf(windowStart to AvailabilityStatus.AVAILABLE)
        val fused =
            fuse(
                sliceOf(mapOf(10L to available, 9L to available, 100L to available)),
                everythingPolls,
                windowStart,
            )

        assertEquals(
            listOf(9L, 10L, 100L),
            fused.days
                .single()
                .cells.keys
                .toList(),
        )
    }

    @Test
    fun `a campsite with no row for the date is unknown`() {
        val fused =
            fuse(
                sliceOf(
                    streams = mapOf(7L to mapOf(windowStart to AvailabilityStatus.AVAILABLE)),
                    days = 2,
                ),
                everythingPolls,
                windowStart,
            )

        assertEquals(AvailabilityStatus.AVAILABLE, fused.days[0].status)
        assertEquals(
            mapOf(7L to AvailabilityCellDto(AvailabilityStatus.UNKNOWN, watchable = false)),
            fused.days[1].cells,
        )
    }

    @Test
    fun `an observation with no campsite id never becomes a cell`() {
        val slice =
            sliceOf(mapOf(7L to mapOf(windowStart to AvailabilityStatus.AVAILABLE)))
                .withExtraObservation(
                    CampsiteDayObservation(null, windowStart, observedAt, AvailabilityStatus.RESERVED),
                )

        assertEquals(
            setOf(7L),
            fuse(slice, everythingPolls, windowStart)
                .days
                .single()
                .cells.keys,
        )
    }

    @Test
    fun `produces one day per date in the window`() {
        val fused =
            fuse(
                sliceOf(
                    streams =
                        mapOf(
                            1L to
                                mapOf(
                                    windowStart to AvailabilityStatus.AVAILABLE,
                                    windowStart.plusDays(1) to AvailabilityStatus.RESERVED,
                                    windowStart.plusDays(2) to AvailabilityStatus.CLOSED,
                                ),
                        ),
                    days = 3,
                ),
                everythingPolls,
                windowStart,
            )

        assertEquals(AvailabilityWindowState.SUCCESS, fused.state)
        assertEquals(
            listOf(
                windowStart.toString() to AvailabilityStatus.AVAILABLE,
                windowStart.plusDays(1).toString() to AvailabilityStatus.RESERVED,
                windowStart.plusDays(2).toString() to AvailabilityStatus.CLOSED,
            ),
            fused.days.map { it.date to it.status },
        )
    }

    // --- watchable ---

    @Test
    fun `a reserved cell on a polling provider is watchable and lifts the day`() {
        val fused =
            fuse(
                sliceOf(
                    mapOf(
                        1L to mapOf(windowStart to AvailabilityStatus.AVAILABLE),
                        2L to mapOf(windowStart to AvailabilityStatus.RESERVED),
                        3L to mapOf(windowStart to AvailabilityStatus.FIRST_COME),
                        4L to mapOf(windowStart to AvailabilityStatus.CLOSED),
                    ),
                ),
                everythingPolls,
                windowStart,
            )

        val day = fused.days.single()
        assertEquals(listOf(false, true, true, false), day.cells.values.map { it.watchable })
        // The rollup is `available`, which is not itself watchable: the day is
        // watchable because one of its cells is.
        assertEquals(AvailabilityStatus.AVAILABLE, day.status)
        assertTrue(day.watchable)
    }

    @Test
    fun `a provider that cannot poll internally has no watchable cell`() {
        val fused =
            fuse(
                sliceOf(mapOf(1L to mapOf(windowStart to AvailabilityStatus.RESERVED))),
                pollingSupported = { false },
                earliestDate = windowStart,
            )

        assertFalse(
            fused.days
                .single()
                .cells
                .getValue(1L)
                .watchable,
        )
        assertFalse(fused.days.single().watchable)
    }

    @Test
    fun `a date before the earliest bookable date is not watchable`() {
        val fused =
            fuse(
                sliceOf(
                    streams =
                        mapOf(
                            1L to
                                mapOf(
                                    windowStart to AvailabilityStatus.RESERVED,
                                    windowStart.plusDays(1) to AvailabilityStatus.RESERVED,
                                ),
                        ),
                    days = 2,
                ),
                everythingPolls,
                earliestDate = windowStart.plusDays(1),
            )

        assertFalse(fused.days[0].watchable)
        assertTrue(fused.days[1].watchable)
    }

    // --- window state, season and cache ---

    @Test
    fun `no campsites at all is empty, not closed for season`() {
        val fused = fuse(sliceOf(emptyMap()), everythingPolls, windowStart)

        assertEquals(AvailabilityWindowState.EMPTY, fused.state)
        assertEquals(emptyList(), fused.days)
        assertNull(fused.season)
        assertNull(fused.cache)
    }

    @Test
    fun `every campsite closed for season closes the week`() {
        val fused =
            fuse(
                sliceOf(
                    streams =
                        mapOf(
                            1L to mapOf(windowStart to AvailabilityStatus.CLOSED),
                            2L to mapOf(windowStart to AvailabilityStatus.CLOSED),
                        ),
                    seasonBlock = AvailabilitySeasonBlock(reopensOn = "2027-05-01"),
                ),
                everythingPolls,
                windowStart,
            )

        assertEquals(AvailabilityWindowState.CLOSED_FOR_SEASON, fused.state)
        assertEquals(AvailabilitySeasonBlock(reopensOn = "2027-05-01"), fused.season)
    }

    @Test
    fun `a closed-for-season week with no reopen date carries no season block`() {
        val fused =
            fuse(
                sliceOf(mapOf(1L to mapOf(windowStart to AvailabilityStatus.CLOSED))),
                everythingPolls,
                windowStart,
            )

        assertEquals(AvailabilityWindowState.CLOSED_FOR_SEASON, fused.state)
        assertNull(fused.season)
    }

    @Test
    fun `one open campsite keeps the week open`() {
        val fused =
            fuse(
                sliceOf(
                    streams =
                        mapOf(
                            1L to mapOf(windowStart to AvailabilityStatus.CLOSED),
                            2L to mapOf(windowStart to AvailabilityStatus.AVAILABLE),
                        ),
                    seasonBlock = AvailabilitySeasonBlock(reopensOn = "2027-05-01"),
                ),
                everythingPolls,
                windowStart,
            )

        assertEquals(AvailabilityWindowState.SUCCESS, fused.state)
        assertNull(fused.season)
    }

    @Test
    fun `carries the stalest freshness block across the streams`() {
        val stale = AvailabilityCacheBlock(hit = true, ageSeconds = 900, ttlSeconds = 600)
        val fused =
            fuse(
                sliceOf(
                    streams = mapOf(1L to mapOf(windowStart to AvailabilityStatus.AVAILABLE)),
                    cacheBlock = stale,
                ),
                everythingPolls,
                windowStart,
            )

        assertEquals(stale, fused.cache)
    }
}

/** The one fused day of a single-date window with one campsite per status. */
private fun fusedDay(vararg statuses: AvailabilityStatus) =
    fuse(
        sliceOf(
            statuses
                .mapIndexed { index, status -> (index + 1).toLong() to mapOf(windowStart to status) }
                .toMap(),
        ),
        everythingPolls,
        windowStart,
    ).days.single()

/**
 * A resolved slice over [days] dates from [windowStart], carrying one campsite
 * per key of [streams] and that campsite's observed statuses by date.
 */
private fun sliceOf(
    streams: Map<Long, Map<LocalDate, AvailabilityStatus>>,
    days: Long = ONE_DAY,
    seasonBlock: AvailabilitySeasonBlock? = null,
    cacheBlock: AvailabilityCacheBlock = freshCache,
): PoiAvailabilitySlice {
    val campsites = streams.keys.map { campsiteFixture(id = it) }
    val observations =
        streams.flatMap { (campsiteId, byDate) ->
            byDate.map { (date, status) -> CampsiteDayObservation(campsiteId, date, observedAt, status) }
        }
    val endDate = windowStart.plusDays(days)
    return PoiAvailabilitySlice(
        poiId = 1L,
        startDate = windowStart,
        endDate = endDate,
        earliestDate = windowStart,
        latestDate = endDate,
        allCampsites = campsites,
        campsites = campsites,
        batch =
            campsites.takeIf { it.isNotEmpty() }?.let {
                AvailabilityObservationBatch(
                    provider = "recgov",
                    startDate = windowStart,
                    endDate = endDate,
                    observations = observations,
                    cacheBlock = cacheBlock,
                    seasonBlock = seasonBlock,
                )
            },
    )
}

private fun PoiAvailabilitySlice.withExtraObservation(observation: CampsiteDayObservation): PoiAvailabilitySlice =
    copy(batch = batch!!.copy(observations = batch.observations + observation))
