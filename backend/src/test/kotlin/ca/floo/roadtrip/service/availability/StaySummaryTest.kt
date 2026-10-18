package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.api.AvailabilityWindowState
import ca.floo.roadtrip.model.api.BulkPoiSummaryDto
import ca.floo.roadtrip.model.api.StayAvailabilityStatus
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campsite
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals

private const val POI_ID = 7L
private const val CACHE_AGE_SECONDS = 240L
private const val CACHE_TTL_SECONDS = 600L

/** A two-night stay: the nights of Sep 11 and Sep 12, checking out on the 13th. */
private val stayStart: LocalDate = LocalDate.of(2026, 9, 11)
private val stayEnd: LocalDate = LocalDate.of(2026, 9, 13)
private val observedAt: Instant = Instant.parse("2026-09-01T00:00:00Z")
private val cache = AvailabilityCacheBlock(hit = true, ageSeconds = CACHE_AGE_SECONDS, ttlSeconds = CACHE_TTL_SECONDS)

class StaySummaryTest {
    @Test
    fun `a site open every night of the stay counts, and one open a single night does not`() {
        val summary =
            summaryOf(
                site(1) to listOf(AvailabilityStatus.AVAILABLE, AvailabilityStatus.AVAILABLE),
                site(2) to listOf(AvailabilityStatus.AVAILABLE, AvailabilityStatus.RESERVED),
            )

        assertEquals(1, summary.openSites)
        assertEquals(2, summary.checkedSites)
        assertEquals(StayAvailabilityStatus.FEW_LEFT, summary.status)
        assertEquals(cache, summary.cache)
    }

    @Test
    fun `more than a few open sites is open`() {
        val open = listOf(AvailabilityStatus.AVAILABLE, AvailabilityStatus.AVAILABLE)
        val summary = summaryOf(*(1L..(FEW_LEFT_MAX_SITES + 1L)).map { site(it) to open }.toTypedArray())

        assertEquals(FEW_LEFT_MAX_SITES + 1, summary.openSites)
        assertEquals(StayAvailabilityStatus.OPEN, summary.status)
    }

    @Test
    fun `first come nights are not bookable, so a walk-up-only stay is full`() {
        val summary =
            summaryOf(site(1) to listOf(AvailabilityStatus.FIRST_COME, AvailabilityStatus.FIRST_COME))

        assertEquals(0, summary.openSites)
        assertEquals(StayAvailabilityStatus.FULL, summary.status)
    }

    @Test
    fun `every site closed every night is closed for the season`() {
        val closed = listOf(AvailabilityStatus.CLOSED, AvailabilityStatus.CLOSED)
        assertEquals(
            StayAvailabilityStatus.CLOSED_FOR_SEASON,
            summaryOf(site(1) to closed, site(2) to closed).status,
        )
    }

    @Test
    fun `a stay that runs into the season's closure is closed, not full`() {
        // Taken the first night, closed from the second: the campground shuts mid-stay.
        val closing = listOf(AvailabilityStatus.RESERVED, AvailabilityStatus.CLOSED)
        assertEquals(
            StayAvailabilityStatus.CLOSED_FOR_SEASON,
            summaryOf(site(1) to closing, site(2) to closing).status,
        )
    }

    @Test
    fun `one site closed among taken ones is full, not a season closure`() {
        val taken = listOf(AvailabilityStatus.RESERVED, AvailabilityStatus.RESERVED)
        val closed = listOf(AvailabilityStatus.CLOSED, AvailabilityStatus.CLOSED)
        assertEquals(StayAvailabilityStatus.FULL, summaryOf(site(1) to taken, site(2) to closed).status)
    }

    @Test
    fun `a stay the provider answered for no night of is no data, not full`() {
        // An unobserved night reads as unknown, the same as one observed unknown.
        val summary = summaryOf(site(1) to emptyList(), site(2) to listOf(AvailabilityStatus.UNKNOWN))

        assertEquals(StayAvailabilityStatus.NO_DATA, summary.status)
        assertEquals(2, summary.checkedSites)
    }

    @Test
    fun `a stay answered for only some nights is no data, not full`() {
        // Bookable the first night, unanswered the second — the month the provider did not return.
        val partly = listOf(AvailabilityStatus.AVAILABLE)
        val summary = summaryOf(site(1) to partly, site(2) to partly)

        assertEquals(0, summary.openSites)
        assertEquals(StayAvailabilityStatus.NO_DATA, summary.status)
    }

    @Test
    fun `a site the provider said nothing about does not stop the rest reading full`() {
        val taken = listOf(AvailabilityStatus.RESERVED, AvailabilityStatus.RESERVED)
        val summary = summaryOf(site(1) to taken, site(2) to taken, site(3) to emptyList())

        assertEquals(StayAvailabilityStatus.FULL, summary.status)
        assertEquals(3, summary.checkedSites)
    }

    @Test
    fun `one ruled-out night is enough to count a site as full`() {
        val summary =
            summaryOf(site(1) to listOf(AvailabilityStatus.AVAILABLE, AvailabilityStatus.RESERVED))

        assertEquals(StayAvailabilityStatus.FULL, summary.status)
    }

    @Test
    fun `no campsites to check is no data`() {
        val summary = summaryOf()

        assertEquals(StayAvailabilityStatus.NO_DATA, summary.status)
        assertEquals(0, summary.checkedSites)
    }

    @Test
    fun `nights outside the stay do not make a site open`() {
        // Bookable the night before and the night after, taken in between.
        val before = CampsiteDayObservation(1, stayStart.minusDays(1), observedAt, AvailabilityStatus.AVAILABLE)
        val after = CampsiteDayObservation(1, stayEnd, observedAt, AvailabilityStatus.AVAILABLE)
        val summary =
            summaryOf(
                site(1) to listOf(AvailabilityStatus.RESERVED, AvailabilityStatus.RESERVED),
                extra = listOf(before, after),
            )

        assertEquals(0, summary.openSites)
        assertEquals(StayAvailabilityStatus.FULL, summary.status)
    }

    @Test
    fun `fusion and the stay summary agree on closed for season for one set of streams`() {
        val closed = listOf(AvailabilityStatus.CLOSED, AvailabilityStatus.CLOSED)
        val open = listOf(AvailabilityStatus.AVAILABLE, AvailabilityStatus.AVAILABLE)
        val taken = listOf(AvailabilityStatus.RESERVED, AvailabilityStatus.RESERVED)

        val shut = listOf(site(1) to closed, site(2) to closed)
        val oneOpen = listOf(site(1) to closed, site(2) to open)
        val closedAndTaken = listOf(site(1) to closed, site(2) to taken)

        listOf(shut to true, oneOpen to false, closedAndTaken to false).forEach { (sites, expectedClosed) ->
            val streams = streamsOf(sites)
            val window = ResolvedDateWindow(stayStart, stayEnd)
            val fused = fuseWindow(streams, window, stayStart, pollingSupported = true, cache = cache, season = null)
            val summary = staySummaryOf(streams, sites.size, cache)

            assertEquals(expectedClosed, fused.state == AvailabilityWindowState.CLOSED_FOR_SEASON)
            assertEquals(expectedClosed, summary.status == StayAvailabilityStatus.CLOSED_FOR_SEASON)
        }
    }

    @Test
    fun `a group keeps the sites that sleep it and the ones that do not say`() {
        val sites = listOf(site(1, maxPeople = 2), site(2, maxPeople = 6), site(3, maxPeople = null))

        assertEquals(listOf(2L, 3L), sites.fittingGroup(groupSize = 4).map { it.id })
        assertEquals(sites, sites.fittingGroup(groupSize = null))
    }
}

private fun site(
    id: Long,
    maxPeople: Int? = null,
): Campsite = campsiteFixture(id = id, campgroundId = POI_ID).copy(maxPeople = maxPeople)

private fun summaryOf(
    vararg sites: Pair<Campsite, List<AvailabilityStatus>>,
    extra: List<CampsiteDayObservation> = emptyList(),
): BulkPoiSummaryDto = staySummaryOf(streamsOf(sites.toList(), extra), sites.size, cache)

private fun streamsOf(
    sites: List<Pair<Campsite, List<AvailabilityStatus>>>,
    extra: List<CampsiteDayObservation> = emptyList(),
): List<CampsiteStream> {
    val observations =
        sites.flatMap { (campsite, nights) ->
            nights.mapIndexed { offset, status ->
                CampsiteDayObservation(campsite.id, stayStart.plusDays(offset.toLong()), observedAt, status)
            }
        } + extra
    return streamsFor(sites.map { it.first }, ResolvedDateWindow(stayStart, stayEnd), observations)
}
