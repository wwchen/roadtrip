package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.service.availability.provider.testCampground
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val firstNight: LocalDate = LocalDate.parse("2026-08-10")
private val observedAtTime: Instant = Instant.parse("2026-08-09T00:00:00Z")
private const val NIGHTS = 3L
private const val LOW_ID = 1L
private const val MID_ID = 2L
private const val HIGH_ID = 3L

class CampgroundWindowTest {
    @Test
    fun `narrowing keeps the streams of the campsites that remain and drops the rest`() {
        val window = windowOver(listOf(campsite(HIGH_ID), campsite(LOW_ID), campsite(MID_ID)))

        val narrowed = window.narrowedTo(listOf(campsite(HIGH_ID), campsite(LOW_ID)))

        assertEquals(listOf(LOW_ID, HIGH_ID), narrowed.streams.map { it.campsiteId })
        assertEquals(2, narrowed.checkedSites())
        assertEquals(3, narrowed.campgroundCampsites.size)
    }

    @Test
    fun `envelopes follow the order of the campsites the read answers for`() {
        val window = windowOver(listOf(campsite(HIGH_ID), campsite(LOW_ID), campsite(MID_ID)))

        val envelopes = window.perCampsiteEnvelopes()

        assertEquals(listOf(HIGH_ID, LOW_ID, MID_ID), envelopes.map { it.response.campsiteId })
        assertEquals(envelopes.map { it.response.campsiteId }, envelopes.map { it.stream.campsiteId })
    }

    @Test
    fun `an envelope carries the provider, campground ref and window of the model`() {
        val window = windowOver(listOf(campsite(LOW_ID)))

        val response = window.perCampsiteEnvelopes().single().response

        assertEquals(BookingProvider.RECGOV.id, response.provider)
        assertEquals(firstNight.toString(), response.startDate)
        assertEquals(firstNight.plusDays(NIGHTS).toString(), response.endDate)
        assertEquals(NIGHTS.toInt(), response.availability.size)
        assertTrue(response.availability.all { it.cells.keys == setOf(LOW_ID) })
    }

    @Test
    fun `a window with no campsites has no envelopes`() {
        assertEquals(emptyList(), windowOver(emptyList()).perCampsiteEnvelopes())
    }

    private fun campsite(id: Long): Campsite = campsiteFixture(id = id)

    private fun windowOver(campsites: List<Campsite>): CampgroundWindow {
        val window = ResolvedDateWindow(firstNight, firstNight.plusDays(NIGHTS))
        val observations =
            campsites.flatMap { campsite ->
                (0 until NIGHTS).map { offset ->
                    CampsiteDayObservation(campsite.id, firstNight.plusDays(offset), observedAtTime, AvailabilityStatus.AVAILABLE)
                }
            }
        return CampgroundWindow(
            poiId = 1L,
            campground = testCampground(bookingProvider = "recgov", bookingProviderRef = "232447"),
            window = window,
            earliestDate = firstNight,
            latestDate = window.endDate,
            campgroundCampsites = campsites,
            campsites = campsites,
            streams = streamsFor(campsites, window, observations),
            provider = BookingProvider.RECGOV,
            campgroundRef = null,
            cache = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
            pollingSupported = true,
            reopensOnByCampsite = emptyMap(),
        )
    }
}
