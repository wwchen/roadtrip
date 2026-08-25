package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.BulkAvailabilityConfig
import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val RANKED_POI_ID = 1L
private const val RATE_LIMITED_POI_ID = 100L
private const val UNKNOWN_POI_ID = 101L
private const val NO_SITES_POI_ID = 103L
private const val CRASHING_POI_ID = 104L

private const val TEST_MAX_POIS = 50
private const val TEST_FAN_OUT_CONCURRENCY = 8
private const val TEST_IP_RATE_LIMIT_PER_MINUTE = 10
private const val NORMAL_SLICE_DELAY_MS = 20L

private val windowStart: LocalDate = LocalDate.of(2026, 9, 1)
private val windowEnd: LocalDate = LocalDate.of(2026, 9, 8)
private val observedAt: Instant = Instant.parse("2026-08-20T00:00:00Z")

class BulkAvailabilityControllerTest {
    @Test
    fun `response entries mirror the requested poi ids in order including duplicates`() {
        runBlocking {
            val response = bulkController().availabilityForPois(request(poiIds = listOf(3L, 1L, 3L)))
            assertEquals(listOf(3L, 1L, 3L), response.pois.map { it.poiId })
        }
    }

    @Test
    fun `campsites below min nights are dropped and the rest sort by run descending`() {
        runBlocking {
            // POI 1 has three sites with longest runs of 1, 5 and 3 nights.
            val response = bulkController().availabilityForPois(request(poiIds = listOf(1L), minNights = 3))
            val campsites = response.pois.single().campsites
            assertNotNull(campsites)
            assertEquals(listOf(5, 3), campsites.map { it.longestRunNights })
        }
    }

    @Test
    fun `a poi whose provider fails reports an error and does not fail its neighbours`() {
        runBlocking {
            val response = bulkController().availabilityForPois(request(poiIds = listOf(1L, RATE_LIMITED_POI_ID)))
            assertNotNull(response.pois[0].campsites)
            assertEquals("rate_limited", response.pois[1].error)
            assertNull(response.pois[1].campsites)
        }
    }

    @Test
    fun `a poi whose lookup throws an unmapped exception reports unknown and does not fail its neighbours`() {
        runBlocking {
            val response =
                bulkController().availabilityForPois(request(poiIds = listOf(1L, CRASHING_POI_ID, 2L)))
            assertNotNull(response.pois[0].campsites)
            assertEquals("unknown", response.pois[1].error)
            assertNull(response.pois[1].campsites)
            assertNotNull(response.pois[2].campsites)
        }
    }

    @Test
    fun `an unknown poi reports not_found`() {
        runBlocking {
            val response = bulkController().availabilityForPois(request(poiIds = listOf(UNKNOWN_POI_ID)))
            assertEquals("not_found", response.pois.single().error)
        }
    }

    @Test
    fun `a poi with no matching campsites resolves with an empty list not an error`() {
        runBlocking {
            val response = bulkController().availabilityForPois(request(poiIds = listOf(NO_SITES_POI_ID)))
            val campsites = response.pois.single().campsites
            assertNotNull(campsites)
            assertEquals(0, campsites.size)
            assertNull(response.pois.single().error)
        }
    }

    @Test
    fun `every poi in one fan out is measured against the same freshness cutoff`() {
        runBlocking {
            val recorder = CutoffRecorder()
            bulkController(recorder = recorder)
                .availabilityForPois(request(poiIds = listOf(1L, 2L, 3L)))
            assertEquals(1, recorder.cutoffs.distinct().size)
        }
    }

    @Test
    fun `concurrent poi resolution never exceeds the configured fan out`() {
        runBlocking {
            val meter = ConcurrencyMeter()
            bulkController(fanOutConcurrency = 2, meter = meter)
                .availabilityForPois(request(poiIds = (1L..8L).toList()))
            assertEquals(2, meter.peak)
        }
    }
}

private fun bulkController(
    fanOutConcurrency: Int = TEST_FAN_OUT_CONCURRENCY,
    recorder: CutoffRecorder? = null,
    meter: ConcurrencyMeter? = null,
): BulkAvailabilityController =
    BulkAvailabilityController(
        sliceLookup = FakePoiAvailabilitySliceLookup(recorder, meter),
        config =
            BulkAvailabilityConfig(
                maxPois = TEST_MAX_POIS,
                fanOutConcurrency = fanOutConcurrency,
                tolerance = Duration.ZERO,
                ipRateLimitPerMinute = TEST_IP_RATE_LIMIT_PER_MINUTE,
            ),
        clock = Clock.systemUTC(),
    )

private fun request(
    poiIds: List<Long>,
    minNights: Int = 1,
    siteTypes: List<String> = emptyList(),
): BulkAvailabilityRequest =
    BulkAvailabilityRequest(
        poiIds = poiIds,
        startDate = null,
        endDate = null,
        minNights = minNights,
        siteTypes = siteTypes,
    )

/** Captures each `freshAtOrAfter` the controller hands to the fake, one per POI resolved. */
private class CutoffRecorder {
    private val recorded = mutableListOf<Instant?>()
    val cutoffs: List<Instant?> get() = recorded

    fun record(freshAtOrAfter: Instant?) {
        recorded += freshAtOrAfter
    }
}

/** Tracks how many fake resolutions are in flight at once, and the peak seen. */
private class ConcurrencyMeter {
    private var current = 0
    var peak = 0
        private set

    suspend fun <T> track(block: suspend () -> T): T {
        current++
        peak = maxOf(peak, current)
        try {
            return block()
        } finally {
            current--
        }
    }
}

/**
 * Stands in for [CampsiteAvailabilityController.poiAvailabilitySlice] without
 * a database. Behaviour is keyed off the requested POI id: most ids resolve
 * to a canned slice, a handful of reserved ids script a provider failure, a
 * not-found, a timeout, or an empty (no matching site type) result.
 */
private class FakePoiAvailabilitySliceLookup(
    private val recorder: CutoffRecorder? = null,
    private val meter: ConcurrencyMeter? = null,
) : PoiAvailabilitySliceLookup {
    override suspend fun poiAvailabilitySlice(
        poiId: Long,
        siteTypes: List<String>,
        startDate: LocalDate?,
        endDate: LocalDate?,
        freshAtOrAfter: Instant?,
    ): PoiAvailabilitySlice {
        recorder?.record(freshAtOrAfter)
        return if (meter != null) meter.track { resolve(poiId) } else resolve(poiId)
    }

    private suspend fun resolve(poiId: Long): PoiAvailabilitySlice =
        when (poiId) {
            RATE_LIMITED_POI_ID -> throw AvailabilityProviderError.RateLimited()
            UNKNOWN_POI_ID -> throw AvailabilityServiceError.NotFound
            CRASHING_POI_ID -> throw RuntimeException("boom: unmapped failure")
            NO_SITES_POI_ID -> noSitesSlice(poiId)
            RANKED_POI_ID -> {
                delay(NORMAL_SLICE_DELAY_MS)
                rankedSlice(poiId)
            }
            else -> {
                delay(NORMAL_SLICE_DELAY_MS)
                simpleSlice(poiId)
            }
        }
}

private fun observationsForRun(
    campsiteId: Long,
    nights: Int,
): List<CampsiteDayObservation> =
    (0 until nights).map { offset ->
        CampsiteDayObservation(campsiteId, windowStart.plusDays(offset.toLong()), observedAt, AvailabilityStatus.AVAILABLE)
    }

private fun batchOf(observations: List<CampsiteDayObservation>): AvailabilityObservationBatch =
    AvailabilityObservationBatch(
        provider = "recgov",
        startDate = windowStart,
        endDate = windowEnd,
        observations = observations,
        cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
    )

/** Three campsites under [poiId] whose longest bookable runs are 1, 5 and 3 nights. */
private fun rankedSlice(poiId: Long): PoiAvailabilitySlice {
    val shortRun = campsiteFixture(id = poiId * 1000 + 1, campgroundId = poiId, name = "Short run")
    val longRun = campsiteFixture(id = poiId * 1000 + 2, campgroundId = poiId, name = "Long run")
    val midRun = campsiteFixture(id = poiId * 1000 + 3, campgroundId = poiId, name = "Mid run")
    val observations =
        observationsForRun(shortRun.id, nights = 1) +
            observationsForRun(longRun.id, nights = 5) +
            observationsForRun(midRun.id, nights = 3)
    val campsites = listOf(shortRun, longRun, midRun)
    return PoiAvailabilitySlice(
        poiId = poiId,
        startDate = windowStart,
        endDate = windowEnd,
        allCampsites = campsites,
        campsites = campsites,
        batch = batchOf(observations),
    )
}

/** One campsite under [poiId] with a single bookable night. */
private fun simpleSlice(poiId: Long): PoiAvailabilitySlice {
    val campsite = campsiteFixture(id = poiId * 1000, campgroundId = poiId)
    return PoiAvailabilitySlice(
        poiId = poiId,
        startDate = windowStart,
        endDate = windowEnd,
        allCampsites = listOf(campsite),
        campsites = listOf(campsite),
        batch = batchOf(observationsForRun(campsite.id, nights = 1)),
    )
}

/** No campsite matched the requested site types: resolved, but nothing to rank. */
private fun noSitesSlice(poiId: Long): PoiAvailabilitySlice =
    PoiAvailabilitySlice(
        poiId = poiId,
        startDate = windowStart,
        endDate = windowEnd,
        allCampsites = emptyList(),
        campsites = emptyList(),
        batch = null,
    )
