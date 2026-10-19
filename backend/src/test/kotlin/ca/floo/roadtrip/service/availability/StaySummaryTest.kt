package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.api.StayAvailabilityStatus
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.domain.Campsite
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
            slice(
                site(1) to listOf(AvailabilityStatus.AVAILABLE, AvailabilityStatus.AVAILABLE),
                site(2) to listOf(AvailabilityStatus.AVAILABLE, AvailabilityStatus.RESERVED),
            ).staySummary()

        assertEquals(1, summary.openSites)
        assertEquals(2, summary.checkedSites)
        assertEquals(StayAvailabilityStatus.FEW_LEFT, summary.status)
    }

    @Test
    fun `more than a few open sites is open`() {
        val open = listOf(AvailabilityStatus.AVAILABLE, AvailabilityStatus.AVAILABLE)
        val summary = slice(*(1L..(FEW_LEFT_MAX_SITES + 1L)).map { site(it) to open }.toTypedArray()).staySummary()

        assertEquals(FEW_LEFT_MAX_SITES + 1, summary.openSites)
        assertEquals(StayAvailabilityStatus.OPEN, summary.status)
    }

    @Test
    fun `first come nights are not bookable, so a walk-up-only stay is full`() {
        val summary =
            slice(site(1) to listOf(AvailabilityStatus.FIRST_COME, AvailabilityStatus.FIRST_COME)).staySummary()

        assertEquals(0, summary.openSites)
        assertEquals(StayAvailabilityStatus.FULL, summary.status)
    }

    @Test
    fun `every site closed every night is closed for the season`() {
        val closed = listOf(AvailabilityStatus.CLOSED, AvailabilityStatus.CLOSED)
        assertEquals(
            StayAvailabilityStatus.CLOSED_FOR_SEASON,
            slice(site(1) to closed, site(2) to closed).staySummary().status,
        )
    }

    @Test
    fun `a stay that runs into the season's closure is closed, not full`() {
        // Taken the first night, closed from the second: the campground shuts mid-stay.
        val closing = listOf(AvailabilityStatus.RESERVED, AvailabilityStatus.CLOSED)
        assertEquals(
            StayAvailabilityStatus.CLOSED_FOR_SEASON,
            slice(site(1) to closing, site(2) to closing).staySummary().status,
        )
    }

    @Test
    fun `one site closed among taken ones is full, not a season closure`() {
        val taken = listOf(AvailabilityStatus.RESERVED, AvailabilityStatus.RESERVED)
        val closed = listOf(AvailabilityStatus.CLOSED, AvailabilityStatus.CLOSED)
        assertEquals(StayAvailabilityStatus.FULL, slice(site(1) to taken, site(2) to closed).staySummary().status)
    }

    @Test
    fun `a stay the provider answered for no night of is no data, not full`() {
        // An unobserved night reads as unknown, the same as one observed unknown.
        val summary = slice(site(1) to emptyList(), site(2) to listOf(AvailabilityStatus.UNKNOWN)).staySummary()

        assertEquals(StayAvailabilityStatus.NO_DATA, summary.status)
        assertEquals(2, summary.checkedSites)
    }

    @Test
    fun `a stay answered for only some nights is no data, not full`() {
        // Bookable the first night, unanswered the second — the month the provider did not return.
        val partly = listOf(AvailabilityStatus.AVAILABLE)
        val summary = slice(site(1) to partly, site(2) to partly).staySummary()

        assertEquals(0, summary.openSites)
        assertEquals(StayAvailabilityStatus.NO_DATA, summary.status)
    }

    @Test
    fun `a site the provider said nothing about does not stop the rest reading full`() {
        val taken = listOf(AvailabilityStatus.RESERVED, AvailabilityStatus.RESERVED)
        val summary = slice(site(1) to taken, site(2) to taken, site(3) to emptyList()).staySummary()

        assertEquals(StayAvailabilityStatus.FULL, summary.status)
        assertEquals(3, summary.checkedSites)
    }

    @Test
    fun `one ruled-out night is enough to count a site as full`() {
        val summary =
            slice(site(1) to listOf(AvailabilityStatus.AVAILABLE, AvailabilityStatus.RESERVED)).staySummary()

        assertEquals(StayAvailabilityStatus.FULL, summary.status)
    }

    @Test
    fun `no campsites to check is no data`() {
        val summary = slice().staySummary()

        assertEquals(StayAvailabilityStatus.NO_DATA, summary.status)
        assertEquals(0, summary.checkedSites)
    }

    @Test
    fun `nights outside the stay do not make a site open`() {
        // Bookable the night before and the night after, taken in between.
        val before = CampsiteDayObservation(1, stayStart.minusDays(1), observedAt, AvailabilityStatus.AVAILABLE)
        val after = CampsiteDayObservation(1, stayEnd, observedAt, AvailabilityStatus.AVAILABLE)
        val summary =
            slice(site(1) to listOf(AvailabilityStatus.RESERVED, AvailabilityStatus.RESERVED), extra = listOf(before, after))
                .staySummary()

        assertEquals(0, summary.openSites)
        assertEquals(StayAvailabilityStatus.FULL, summary.status)
    }

    @Test
    fun `a summary reports the oldest observation behind it as an instant`() {
        val older = Instant.parse("2026-08-30T06:30:00Z")
        val newer = Instant.parse("2026-09-01T00:00:00Z")
        val observations =
            listOf(
                CampsiteDayObservation(1, stayStart, newer, AvailabilityStatus.AVAILABLE),
                CampsiteDayObservation(1, stayStart.plusDays(1), older, AvailabilityStatus.AVAILABLE),
                CampsiteDayObservation(2, stayStart, newer, AvailabilityStatus.RESERVED),
            )
        val summary = sliceOver(listOf(site(1), site(2)), observations).staySummary()

        // The instant itself, not an age: the cache block's age is relative to a clock the browser never saw.
        assertEquals("2026-08-30T06:30:00Z", summary.observedAt)
    }

    @Test
    fun `from_cache is true when the loader served without asking the provider`() {
        val served = listOf(AvailabilityStatus.AVAILABLE, AvailabilityStatus.AVAILABLE)

        assertEquals(true, slice(site(1) to served).staySummary().fromCache)
        assertEquals(false, slice(site(1) to served, cacheBlock = cache.copy(hit = false)).staySummary().fromCache)
    }

    @Test
    fun `observed_at is absent when no observation backs the answer`() {
        val served = listOf(AvailabilityStatus.AVAILABLE, AvailabilityStatus.AVAILABLE)
        val resolved = slice(site(1) to served)

        // The site_type filter left no campsites, so nothing was read: there is no batch.
        val noBatch = resolved.copy(campsites = emptyList(), batch = null).staySummary()
        // group_size emptied the list after the slice resolved: the batch is there, no site is in the answer.
        val noneFit = resolved.copy(campsites = emptyList()).staySummary()

        for (summary in listOf(noBatch, noneFit)) {
            assertEquals(0, summary.checkedSites)
            assertNull(summary.observedAt)
            assertEquals(false, summary.fromCache)
            assertEquals(StayAvailabilityStatus.NO_DATA, summary.status)
        }
        assertEquals(observedAt.toString(), resolved.staySummary().observedAt)
    }

    @Test
    fun `observed_at is absent when campsites were checked but the provider returned nothing`() {
        // The batch is there and the site is in the answer, but not one night was observed.
        val summary = slice(site(1) to emptyList()).staySummary()

        assertEquals(1, summary.checkedSites)
        assertNull(summary.observedAt)
        assertEquals(StayAvailabilityStatus.NO_DATA, summary.status)
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

/** A slice over the stay, each site's nights given in order from [stayStart]. */
private fun slice(
    vararg sites: Pair<Campsite, List<AvailabilityStatus>>,
    extra: List<CampsiteDayObservation> = emptyList(),
    cacheBlock: AvailabilityCacheBlock = cache,
): PoiAvailabilitySlice {
    val observations =
        sites.flatMap { (campsite, nights) ->
            nights.mapIndexed { offset, status ->
                CampsiteDayObservation(campsite.id, stayStart.plusDays(offset.toLong()), observedAt, status)
            }
        } + extra
    return sliceOver(sites.map { it.first }, observations, cacheBlock)
}

/** A slice over the stay whose batch holds exactly [observations]. */
private fun sliceOver(
    campsites: List<Campsite>,
    observations: List<CampsiteDayObservation>,
    cacheBlock: AvailabilityCacheBlock = cache,
): PoiAvailabilitySlice =
    PoiAvailabilitySlice(
        poiId = POI_ID,
        startDate = stayStart,
        endDate = stayEnd,
        earliestDate = stayStart,
        latestDate = null,
        allCampsites = campsites,
        campsites = campsites,
        batch =
            AvailabilityObservationBatch(
                provider = "recgov",
                startDate = stayStart,
                endDate = stayEnd,
                observations = observations,
                cacheBlock = cacheBlock,
            ),
        pollingSupported = true,
    )
