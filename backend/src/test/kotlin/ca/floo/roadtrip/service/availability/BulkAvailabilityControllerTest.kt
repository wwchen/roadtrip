package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.BulkAvailabilityConfig
import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.api.StayAvailabilityStatus
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.domain.CampsiteKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val RANKED_POI_ID = 1L
private const val RATE_LIMITED_POI_ID = 100L
private const val UNKNOWN_POI_ID = 101L
private const val NO_SITES_POI_ID = 103L
private const val CRASHING_POI_ID = 104L
private const val SLOW_POI_ID = 105L
private const val STRAY_CANCELLATION_POI_ID = 106L
private const val STUCK_POI_ID = 107L

/** How long the stuck POI's read takes, deaf to cancellation: far past any deadline under test. */
private const val STUCK_READ_MS = 600_000L
private const val SLOW_SLICE_DELAY_MS = 300L
private const val TEST_PER_POI_TIMEOUT_MS = 100L
private const val TEST_STREAM_TIMEOUT_MS = 100L
private const val TEST_GENEROUS_TIMEOUT_SECONDS = 30L

private const val TEST_MAX_POIS = 50
private const val TEST_FAN_OUT_CONCURRENCY = 8
private const val TEST_IP_POI_LIMIT_PER_MINUTE = 1_000
private const val NORMAL_SLICE_DELAY_MS = 20L

private val windowStart: LocalDate = LocalDate.of(2026, 9, 1)
private val windowEnd: LocalDate = LocalDate.of(2026, 9, 8)

/** rec.gov's horizon, so the faked slice's ceiling is a real provider's. */
private const val BOOKING_HORIZON_DAYS = 180L
private val windowLatest: LocalDate = windowStart.plusDays(BOOKING_HORIZON_DAYS)
private val observedAt: Instant = Instant.parse("2026-08-20T00:00:00Z")

class BulkAvailabilityControllerTest {
    @Test
    fun `response entries mirror the requested poi ids in order including duplicates`() {
        runTest {
            val response = bulkController().availabilityForPois(request(poiIds = listOf(3L, 1L, 3L)))
            assertEquals(listOf(3L, 1L, 3L), response.pois.map { it.poiId })
        }
    }

    @Test
    fun `campsites below min nights are dropped and the rest sort by run descending`() {
        runTest {
            // POI 1 has three sites with longest runs of 1, 5 and 3 nights.
            val response = bulkController().availabilityForPois(request(poiIds = listOf(1L), minNights = 3))
            val campsites = response.pois.single().campsites
            assertNotNull(campsites)
            assertEquals(listOf(5, 3), campsites.map { it.longestRunNights })
        }
    }

    @Test
    fun `a poi whose provider fails reports an error and does not fail its neighbours`() {
        runTest {
            val response = bulkController().availabilityForPois(request(poiIds = listOf(1L, RATE_LIMITED_POI_ID)))
            assertNotNull(response.pois[0].campsites)
            assertEquals("rate_limited", response.pois[1].error)
            assertNull(response.pois[1].campsites)
        }
    }

    @Test
    fun `a poi whose lookup throws an unmapped exception reports unknown and does not fail its neighbours`() {
        runTest {
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
        runTest {
            val response = bulkController().availabilityForPois(request(poiIds = listOf(UNKNOWN_POI_ID)))
            assertEquals("not_found", response.pois.single().error)
        }
    }

    @Test
    fun `a poi with no matching campsites resolves with an empty list not an error`() {
        runTest {
            val response = bulkController().availabilityForPois(request(poiIds = listOf(NO_SITES_POI_ID)))
            val campsites = response.pois.single().campsites
            assertNotNull(campsites)
            assertEquals(0, campsites.size)
            assertNull(response.pois.single().error)
        }
    }

    @Test
    fun `the stay stream answers every poi once, with its summary or its error`() {
        runTest {
            // POI 1's three sites are bookable for runs of 1, 5 and 3 nights from the
            // window's start and unanswered after, so none is open for every night of the
            // seven-night window and nothing rules any out: no data, not full.
            val answers = bulkController().stayAnswers(stayRequest(listOf(1L, RATE_LIMITED_POI_ID))).toList()

            assertEquals(setOf(1L, RATE_LIMITED_POI_ID), answers.map { it.poiId }.toSet())
            val summary = assertNotNull(answers.single { it.poiId == 1L }.summary)
            assertEquals(StayAvailabilityStatus.NO_DATA, summary.status)
            assertEquals(0, summary.openSites)
            assertEquals(3, summary.checkedSites)
            val failed = answers.single { it.poiId == RATE_LIMITED_POI_ID }
            assertEquals("rate_limited", failed.error)
            assertNull(failed.summary)
        }
    }

    @Test
    fun `stay answers arrive as they land, not in request order`() {
        runTest {
            val answers = bulkController().stayAnswers(stayRequest(listOf(SLOW_POI_ID, 2L))).toList()

            assertEquals(listOf(2L, SLOW_POI_ID), answers.map { it.poiId })
        }
    }

    @Test
    fun `a poi that outlives the per-poi timeout answers timeout, and the stream still ends`() {
        runTest {
            val answers =
                bulkController(perPoiTimeout = Duration.ofMillis(TEST_PER_POI_TIMEOUT_MS))
                    .stayAnswers(stayRequest(listOf(SLOW_POI_ID, 2L)))
                    .toList()

            assertEquals(STAY_TIMEOUT_ERROR, answers.single { it.poiId == SLOW_POI_ID }.error)
            assertNotNull(answers.single { it.poiId == 2L }.summary)
        }
    }

    @Test
    fun `past the stream's deadline every poi yet to answer answers timeout, and the stream still ends`() {
        runTest {
            val answers =
                bulkController(streamTimeout = Duration.ofMillis(TEST_STREAM_TIMEOUT_MS))
                    .stayAnswers(stayRequest(listOf(SLOW_POI_ID, 2L, SLOW_POI_ID)))
                    .toList()

            assertEquals(listOf(2L, SLOW_POI_ID, SLOW_POI_ID), answers.map { it.poiId })
            assertEquals(listOf(STAY_TIMEOUT_ERROR, STAY_TIMEOUT_ERROR), answers.filter { it.poiId == SLOW_POI_ID }.map { it.error })
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a read that cannot be cancelled does not hold the stream past its deadline`() {
        runTest {
            val answers =
                bulkController(streamTimeout = Duration.ofMillis(TEST_STREAM_TIMEOUT_MS))
                    .stayAnswers(stayRequest(listOf(STUCK_POI_ID, 2L)))
                    .toList()

            assertEquals(STAY_TIMEOUT_ERROR, answers.single { it.poiId == STUCK_POI_ID }.error)
            assertNotNull(answers.single { it.poiId == 2L }.summary)
            assertEquals(TEST_STREAM_TIMEOUT_MS, currentTime, "the stream ended at its deadline, not when the read did")
        }
    }

    @Test
    fun `a read that ends without an answer still answers, as the unclassified failure`() {
        runTest {
            val answers = bulkController().stayAnswers(stayRequest(listOf(STRAY_CANCELLATION_POI_ID, 2L))).toList()

            assertEquals(setOf(STRAY_CANCELLATION_POI_ID, 2L), answers.map { it.poiId }.toSet())
            assertEquals(AvailabilityProviderError.Unknown.CODE, answers.single { it.poiId == STRAY_CANCELLATION_POI_ID }.error)
        }
    }

    @Test
    fun `the stay stream never runs more pois at once than the configured fan out`() {
        runTest {
            val meter = ConcurrencyMeter()
            bulkController(fanOutConcurrency = 2, meter = meter).stayAnswers(stayRequest((1L..8L).toList())).toList()
            assertEquals(2, meter.peak)
        }
    }

    @Test
    fun `a group size leaves out the campsites too small for it, in both reads`() {
        runTest {
            // POI 1's long-run site sleeps 2; the other two state no capacity.
            val campsites =
                bulkController()
                    .availabilityForPois(request(poiIds = listOf(1L), groupSize = 4))
                    .pois
                    .single()
                    .campsites
            assertEquals(listOf(3, 1), assertNotNull(campsites).map { it.longestRunNights })

            val summary =
                bulkController()
                    .stayAnswers(stayRequest(listOf(1L), groupSize = 4))
                    .toList()
                    .single()
                    .summary
            assertEquals(2, assertNotNull(summary).checkedSites)
        }
    }

    @Test
    fun `a fresh request only reuses observations younger than the fresh tolerance`() {
        runTest {
            val now = Instant.parse("2026-10-12T12:00:00Z")
            val config =
                BulkAvailabilityConfig(
                    maxPois = TEST_MAX_POIS,
                    fanOutConcurrency = TEST_FAN_OUT_CONCURRENCY,
                    tolerance = Duration.ofHours(2),
                    ipPoiLimitPerMinute = TEST_IP_POI_LIMIT_PER_MINUTE,
                    freshTolerance = Duration.ofMinutes(1),
                )
            val recorder = CutoffRecorder()
            val controller =
                BulkAvailabilityController(
                    sliceLookup = FakePoiAvailabilitySliceLookup(recorder),
                    config = config,
                    clock = Clock.fixed(now, ZoneOffset.UTC),
                )

            controller.availabilityForPois(request(poiIds = listOf(2L)))
            controller.availabilityForPois(request(poiIds = listOf(2L), fresh = true))

            assertEquals(listOf(now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(1))), recorder.cutoffs)
        }
    }

    @Test
    fun `every poi in one fan out is measured against the same freshness cutoff`() {
        runTest {
            val recorder = CutoffRecorder()
            bulkController(recorder = recorder)
                .availabilityForPois(request(poiIds = listOf(1L, 2L, 3L)))
            assertEquals(1, recorder.cutoffs.distinct().size)
        }
    }

    @Test
    fun `concurrent poi resolution never exceeds the configured fan out`() {
        runTest {
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
    perPoiTimeout: Duration = Duration.ofSeconds(TEST_GENEROUS_TIMEOUT_SECONDS),
    streamTimeout: Duration = Duration.ofSeconds(TEST_GENEROUS_TIMEOUT_SECONDS),
): BulkAvailabilityController =
    BulkAvailabilityController(
        sliceLookup = FakePoiAvailabilitySliceLookup(recorder, meter),
        config =
            BulkAvailabilityConfig(
                maxPois = TEST_MAX_POIS,
                fanOutConcurrency = fanOutConcurrency,
                tolerance = Duration.ZERO,
                ipPoiLimitPerMinute = TEST_IP_POI_LIMIT_PER_MINUTE,
                perPoiTimeout = perPoiTimeout,
                streamTimeout = streamTimeout,
            ),
        clock = Clock.systemUTC(),
    )

private fun request(
    poiIds: List<Long>,
    minNights: Int = 1,
    siteTypes: List<CampsiteKind> = emptyList(),
    groupSize: Int? = null,
    fresh: Boolean = false,
): BulkAvailabilityRequest =
    BulkAvailabilityRequest(
        poiIds = poiIds,
        startDate = null,
        endDate = null,
        minNights = minNights,
        siteTypes = siteTypes,
        groupSize = groupSize,
        fresh = fresh,
    )

private fun stayRequest(
    poiIds: List<Long>,
    groupSize: Int? = null,
): StayCheckRequest =
    StayCheckRequest(
        poiIds = poiIds,
        startDate = windowStart,
        endDate = windowEnd,
        siteTypes = emptyList(),
        groupSize = groupSize,
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
        siteTypes: List<CampsiteKind>,
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
            // A cancellation nobody asked for — what a provider's own `withTimeout` throws.
            STRAY_CANCELLATION_POI_ID -> throw CancellationException("not this read's caller")
            // A blocking call in progress: nothing the caller does can cut it short.
            STUCK_POI_ID -> {
                withContext(NonCancellable) { delay(STUCK_READ_MS) }
                simpleSlice(poiId)
            }
            SLOW_POI_ID -> {
                delay(SLOW_SLICE_DELAY_MS)
                simpleSlice(poiId)
            }
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

/** Three campsites under [poiId] whose longest bookable runs are 1, 5 and 3 nights; the 5-night one sleeps 2. */
private fun rankedSlice(poiId: Long): PoiAvailabilitySlice {
    val shortRun = campsiteFixture(id = poiId * 1000 + 1, campgroundId = poiId, name = "Short run")
    val longRun = campsiteFixture(id = poiId * 1000 + 2, campgroundId = poiId, name = "Long run").copy(maxPeople = 2)
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
        earliestDate = windowStart,
        latestDate = windowLatest,
        allCampsites = campsites,
        campsites = campsites,
        batch = batchOf(observations),
        pollingSupported = true,
    )
}

/** One campsite under [poiId] with a single bookable night. */
private fun simpleSlice(poiId: Long): PoiAvailabilitySlice {
    val campsite = campsiteFixture(id = poiId * 1000, campgroundId = poiId)
    return PoiAvailabilitySlice(
        poiId = poiId,
        startDate = windowStart,
        endDate = windowEnd,
        earliestDate = windowStart,
        latestDate = windowLatest,
        allCampsites = listOf(campsite),
        campsites = listOf(campsite),
        batch = batchOf(observationsForRun(campsite.id, nights = 1)),
        pollingSupported = true,
    )
}

/** No campsite matched the requested site types: resolved, but nothing to rank. */
private fun noSitesSlice(poiId: Long): PoiAvailabilitySlice =
    PoiAvailabilitySlice(
        poiId = poiId,
        startDate = windowStart,
        endDate = windowEnd,
        earliestDate = windowStart,
        latestDate = windowLatest,
        allCampsites = emptyList(),
        campsites = emptyList(),
        batch = null,
        pollingSupported = true,
    )
