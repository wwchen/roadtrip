package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.api.AvailabilityWindowState
import ca.floo.roadtrip.model.api.StayAvailabilityStatus
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.route.common.encodeApiJson
import ca.floo.roadtrip.service.availability.provider.testCampground
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun `a window whose filter matched no campsite derives an empty answer throughout`() {
        val noMatch = noCampsitesMatched()

        assertEquals(emptyList(), noMatch.perCampsiteEnvelopes())
        assertEquals(0, noMatch.checkedSites())
        assertEquals(AvailabilityWindowState.EMPTY, noMatch.fusedWindow().state)
        assertNull(noMatch.fusedWindow().cache)
        assertEquals(StayAvailabilityStatus.NO_DATA, noMatch.staySummary().status)
        assertNull(noMatch.staySummary().cache)
    }

    @Test
    fun `narrowing to a group size leaves a window that matched no campsite unchanged`() {
        val noMatch = noCampsitesMatched()

        assertEquals(noMatch, noMatch.narrowedToGroup(groupSize = 4))
    }

    @Test
    fun `an unobserved night ships no cell while the stream still reads unknown`() {
        val observations =
            listOf(
                observation(LOW_ID, 0, AvailabilityStatus.AVAILABLE),
                observation(LOW_ID, 2, AvailabilityStatus.UNKNOWN),
            )
        val window = windowOver(listOf(campsite(LOW_ID)), observations)

        val days =
            window
                .perCampsiteEnvelopes()
                .single()
                .response.availability

        assertEquals(
            setOf(AvailabilityStatus.UNKNOWN),
            window.streams
                .single()
                .statuses
                .drop(1)
                .toSet(),
        )
        assertEquals(listOf(setOf(LOW_ID), emptySet(), setOf(LOW_ID)), days.map { it.cells.keys })
        assertEquals(AvailabilityStatus.UNKNOWN, days[1].status)
        assertEquals(AvailabilityStatus.UNKNOWN, days[2].cells.getValue(LOW_ID).status)
    }

    @Test
    fun `a window the provider said nothing about reads empty, not success`() {
        val window = windowOver(listOf(campsite(LOW_ID)), observations = emptyList())

        val response = window.perCampsiteEnvelopes().single().response

        assertEquals("empty", response.state)
        assertTrue(response.availability.all { it.cells.isEmpty() })
    }

    @Test
    fun `an envelope serializes the campground ref as scope_ref, and omits it when there is none`() {
        val ref = BookingProviderRef.RecGov(facilityId = "232447")

        val withRef = windowOver(listOf(campsite(LOW_ID)), campgroundRef = ref).perCampsiteEnvelopes().single().response
        val withoutRef = windowOver(listOf(campsite(LOW_ID))).perCampsiteEnvelopes().single().response

        assertEquals(
            "232447",
            Json
                .parseToJsonElement(encodeApiJson(withRef))
                .jsonObject["scope_ref"]
                ?.jsonPrimitive
                ?.content,
        )
        assertNull(Json.parseToJsonElement(encodeApiJson(withoutRef)).jsonObject["scope_ref"])
    }

    private fun noCampsitesMatched(): CampgroundWindow.NoCampsitesMatched {
        val window = ResolvedDateWindow(firstNight, firstNight.plusDays(NIGHTS))
        return CampgroundWindow.NoCampsitesMatched(
            poiId = 1L,
            campground = testCampground(bookingProvider = "recgov", bookingProviderRef = "232447"),
            window = window,
            earliestDate = firstNight,
            latestDate = window.endDate,
            campgroundCampsites = listOf(campsite(LOW_ID)),
        )
    }

    private fun campsite(id: Long): Campsite = campsiteFixture(id = id)

    private fun observation(
        campsiteId: Long,
        offset: Int,
        status: AvailabilityStatus,
    ) = CampsiteDayObservation(campsiteId, firstNight.plusDays(offset.toLong()), observedAtTime, status)

    private fun windowOver(
        campsites: List<Campsite>,
        observations: List<CampsiteDayObservation> =
            campsites.flatMap { campsite ->
                (0 until NIGHTS.toInt()).map { offset -> observation(campsite.id, offset, AvailabilityStatus.AVAILABLE) }
            },
        campgroundRef: BookingProviderRef? = null,
    ): CampgroundWindow.Answered {
        val window = ResolvedDateWindow(firstNight, firstNight.plusDays(NIGHTS))
        return CampgroundWindow.Answered(
            poiId = 1L,
            campground = testCampground(bookingProvider = "recgov", bookingProviderRef = "232447"),
            window = window,
            earliestDate = firstNight,
            latestDate = window.endDate,
            campgroundCampsites = campsites,
            campsites = campsites,
            streams = streamsFor(campsites, window, observations),
            provider = BookingProvider.RECGOV,
            campgroundRef = campgroundRef,
            cache = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
            pollingSupported = true,
            reopensOnByCampsite = emptyMap(),
        )
    }
}
