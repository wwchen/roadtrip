package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val windowStart: LocalDate = LocalDate.parse("2026-08-10")
private const val WINDOW_DAYS = 3L
private val window = ResolvedDateWindow(windowStart, windowStart.plusDays(WINDOW_DAYS))
private val earlier: Instant = Instant.parse("2026-08-08T00:00:00Z")
private val later: Instant = Instant.parse("2026-08-09T00:00:00Z")
private const val SITE_LOW = 3L
private const val SITE_HIGH = 9L
private const val SITE_OUTSIDE = 99L

class CampsiteStreamTest {
    @Test
    fun `a campsite with no observation is a stream of unknowns`() {
        val streams = streamsFor(listOf(campsiteFixture(id = SITE_LOW)), window, emptyList())

        assertEquals(1, streams.size)
        assertEquals(SITE_LOW, streams.single().campsiteId)
        assertEquals(List(WINDOW_DAYS.toInt()) { AvailabilityStatus.UNKNOWN }, streams.single().statuses)
    }

    @Test
    fun `statuses follow the window's dates in order`() {
        val observations =
            listOf(
                observation(SITE_LOW, 2, AvailabilityStatus.RESERVED),
                observation(SITE_LOW, 0, AvailabilityStatus.AVAILABLE),
            )

        val stream = streamsFor(listOf(campsiteFixture(id = SITE_LOW)), window, observations).single()

        assertEquals(
            listOf(AvailabilityStatus.AVAILABLE, AvailabilityStatus.UNKNOWN, AvailabilityStatus.RESERVED),
            stream.statuses,
        )
        assertEquals(WINDOW_DAYS.toInt(), stream.statuses.size)
    }

    @Test
    fun `an observation outside the window is ignored`() {
        val observations =
            listOf(
                observation(SITE_LOW, -1, AvailabilityStatus.AVAILABLE),
                observation(SITE_LOW, WINDOW_DAYS.toInt(), AvailabilityStatus.AVAILABLE),
            )

        val stream = streamsFor(listOf(campsiteFixture(id = SITE_LOW)), window, observations).single()

        assertTrue(stream.statuses.all { it == AvailabilityStatus.UNKNOWN })
    }

    @Test
    fun `an observation for a campsite outside the list makes no stream`() {
        val streams =
            streamsFor(
                listOf(campsiteFixture(id = SITE_LOW)),
                window,
                listOf(observation(SITE_OUTSIDE, 0, AvailabilityStatus.AVAILABLE)),
            )

        assertEquals(listOf(SITE_LOW), streams.map { it.campsiteId })
        assertEquals(AvailabilityStatus.UNKNOWN, streams.single().statuses.first())
    }

    @Test
    fun `a campsite named twice makes one stream`() {
        val campsites = listOf(SITE_HIGH, SITE_LOW, SITE_HIGH).map { campsiteFixture(id = it) }

        val streams = streamsFor(campsites, window, emptyList())

        assertEquals(listOf(SITE_LOW, SITE_HIGH), streams.map { it.campsiteId })
    }

    @Test
    fun `the latest observation for a date wins`() {
        val observations =
            listOf(
                observation(SITE_LOW, 1, AvailabilityStatus.RESERVED, later),
                observation(SITE_LOW, 1, AvailabilityStatus.AVAILABLE, earlier),
            )

        val stream = streamsFor(listOf(campsiteFixture(id = SITE_LOW)), window, observations).single()

        assertEquals(AvailabilityStatus.RESERVED, stream.statuses[1])
    }
}

private fun observation(
    campsiteId: Long,
    dayOffset: Int,
    status: AvailabilityStatus,
    observedAt: Instant = later,
) = CampsiteDayObservation(campsiteId, windowStart.plusDays(dayOffset.toLong()), observedAt, status)
