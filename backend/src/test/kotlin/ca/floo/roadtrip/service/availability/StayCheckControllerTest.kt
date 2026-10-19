package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.StayCheckConfig
import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.api.StayAnswerDto
import ca.floo.roadtrip.model.api.StayAvailabilityStatus
import ca.floo.roadtrip.model.api.StayCheckOutcome
import ca.floo.roadtrip.model.api.StayFailureReason
import ca.floo.roadtrip.model.api.StayOutcome
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
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
import kotlin.test.assertTrue

private const val RANKED_POI_ID = 1L
private const val RATE_LIMITED_POI_ID = 100L
private const val UNKNOWN_POI_ID = 101L
private const val NO_SITES_POI_ID = 103L
private const val CRASHING_POI_ID = 104L
private const val SLOW_POI_ID = 105L
private const val STRAY_CANCELLATION_POI_ID = 106L
private const val STUCK_POI_ID = 107L
private const val BEYOND_HORIZON_POI_ID = 108L
private const val BLOCKED_POI_ID = 109L

/** How long the stuck POI's read takes, deaf to cancellation: far past any deadline under test. */
private const val STUCK_READ_MS = 600_000L

/** How long the blocked POI's read takes: far past any deadline under test. */
private const val BLOCKED_READ_MS = 600_000L

private const val SLOW_SLICE_DELAY_MS = 300L
private const val TEST_PER_POI_TIMEOUT_MS = 100L
private const val TEST_STREAM_TIMEOUT_MS = 100L
private const val TEST_GENEROUS_TIMEOUT_SECONDS = 30L

private const val TEST_MAX_POIS = 50
private const val TEST_FAN_OUT_CONCURRENCY = 8
private const val TEST_IP_POI_LIMIT_PER_MINUTE = 1_000
private const val NORMAL_SLICE_DELAY_MS = 20L

/** Strictly between the floor and ceiling in the stay max-age test, so the cutoff can only have come from the request. */
private const val STAY_MAX_AGE_SECONDS = 600L

private val windowStart: LocalDate = LocalDate.of(2026, 9, 1)
private val windowEnd: LocalDate = LocalDate.of(2026, 9, 8)

/** rec.gov's horizon, so the faked slice's ceiling is a real provider's. */
private const val BOOKING_HORIZON_DAYS = 180L
private val windowLatest: LocalDate = windowStart.plusDays(BOOKING_HORIZON_DAYS)
private val observedAt: Instant = Instant.parse("2026-08-20T00:00:00Z")

class StayCheckControllerTest {
    @Test
    fun `a poi whose lookup throws an unmapped exception answers failed, unknown, and does not fail its neighbours`() {
        runTest {
            val answers = stayCheckController().stayAnswers(stayRequest(listOf(1L, CRASHING_POI_ID, 2L))).answers()

            assertEquals(setOf(1L, CRASHING_POI_ID, 2L), answers.map { it.poiId }.toSet())
            val crashed = answers.single { it.poiId == CRASHING_POI_ID }
            assertEquals(StayOutcome.FAILED, crashed.outcome)
            assertEquals(StayFailureReason.UNKNOWN, crashed.reason)
            assertNull(crashed.stay)
            val neighbours = answers.filter { it.poiId != CRASHING_POI_ID }
            assertEquals(listOf(StayOutcome.ANSWERED, StayOutcome.ANSWERED), neighbours.map { it.outcome })
        }
    }

    @Test
    fun `an unknown poi answers failed, not_found`() {
        runTest {
            val answer = stayCheckController().stayAnswers(stayRequest(listOf(UNKNOWN_POI_ID))).answers().single()

            assertEquals(StayOutcome.FAILED, answer.outcome)
            assertEquals(StayFailureReason.NOT_FOUND, answer.reason)
            assertNull(answer.stay)
        }
    }

    @Test
    fun `a poi with no matching campsites answers with no_data over zero sites, not a failure`() {
        runTest {
            val answer = stayCheckController().stayAnswers(stayRequest(listOf(NO_SITES_POI_ID))).answers().single()

            assertEquals(StayOutcome.ANSWERED, answer.outcome)
            val stay = assertNotNull(answer.stay)
            assertEquals(StayAvailabilityStatus.NO_DATA, stay.status)
            assertEquals(0, stay.checkedSites)
            assertNull(stay.observedAt)
            assertNull(answer.reason)
        }
    }

    @Test
    fun `a resolved poi answers with outcome answered and a stay`() {
        runTest {
            // POI 1's three sites are bookable for runs of 1, 5 and 3 nights from the
            // window's start and unanswered after, so none is open for every night of the
            // seven-night window and nothing rules any out: no data, not full.
            val answer = stayCheckController().stayAnswers(stayRequest(listOf(1L))).answers().single()

            assertEquals(1L, answer.poiId)
            assertEquals(StayOutcome.ANSWERED, answer.outcome)
            val stay = assertNotNull(answer.stay)
            assertEquals(StayAvailabilityStatus.NO_DATA, stay.status)
            assertEquals(0, stay.openSites)
            assertEquals(3, stay.checkedSites)
            assertEquals(observedAt.toString(), stay.observedAt)
            assertNull(answer.reason)
            assertNull(answer.retryable)
        }
    }

    @Test
    fun `a rate-limited poi answers failed, rate_limited, retryable`() {
        runTest {
            val answers = stayCheckController().stayAnswers(stayRequest(listOf(1L, RATE_LIMITED_POI_ID))).answers()

            assertEquals(setOf(1L, RATE_LIMITED_POI_ID), answers.map { it.poiId }.toSet())
            val failed = answers.single { it.poiId == RATE_LIMITED_POI_ID }
            assertEquals(StayOutcome.FAILED, failed.outcome)
            assertEquals(StayFailureReason.RATE_LIMITED, failed.reason)
            assertEquals(true, failed.retryable)
            assertNull(failed.stay)
        }
    }

    @Test
    fun `a poi past its booking horizon answers refused with no retryable`() {
        runTest {
            val answer = stayCheckController().stayAnswers(stayRequest(listOf(BEYOND_HORIZON_POI_ID))).answers().single()

            assertEquals(StayOutcome.REFUSED, answer.outcome)
            assertEquals(StayFailureReason.BEYOND_BOOKING_HORIZON, answer.reason)
            assertNull(answer.retryable)
            assertNull(answer.stay)
        }
    }

    @Test
    fun `duplicate poi ids each get their own answer`() {
        runTest {
            val answers = stayCheckController().stayAnswers(stayRequest(listOf(7L, 7L))).answers()

            assertEquals(listOf(7L, 7L), answers.map { it.poiId })
            assertEquals(listOf(StayOutcome.ANSWERED, StayOutcome.ANSWERED), answers.map { it.outcome })
        }
    }

    @Test
    fun `stay answers arrive as they land, not in request order`() {
        runTest {
            val answers = stayCheckController().stayAnswers(stayRequest(listOf(SLOW_POI_ID, 2L))).answers()

            assertEquals(listOf(2L, SLOW_POI_ID), answers.map { it.poiId })
        }
    }

    @Test
    fun `a poi that outlives the per-poi timeout answers timed_out with no reason`() {
        runTest {
            val answers =
                stayCheckController(perPoiTimeout = Duration.ofMillis(TEST_PER_POI_TIMEOUT_MS))
                    .stayAnswers(stayRequest(listOf(SLOW_POI_ID, 2L)))
                    .answers()

            val timedOut = answers.single { it.poiId == SLOW_POI_ID }
            assertEquals(StayOutcome.TIMED_OUT, timedOut.outcome)
            assertNull(timedOut.reason)
            assertNull(timedOut.retryable)
            assertNull(timedOut.stay)
            assertEquals(StayOutcome.ANSWERED, answers.single { it.poiId == 2L }.outcome)
        }
    }

    @Test
    fun `past the stream's deadline every poi yet to answer answers timeout, and the stream still ends`() {
        runTest {
            val answers =
                stayCheckController(streamTimeout = Duration.ofMillis(TEST_STREAM_TIMEOUT_MS))
                    .stayAnswers(stayRequest(listOf(SLOW_POI_ID, 2L, SLOW_POI_ID)))
                    .answers()

            assertEquals(listOf(2L, SLOW_POI_ID, SLOW_POI_ID), answers.map { it.poiId })
            assertEquals(
                listOf(StayOutcome.TIMED_OUT, StayOutcome.TIMED_OUT),
                answers.filter { it.poiId == SLOW_POI_ID }.map { it.outcome },
            )
        }
    }

    @Test
    fun `a check where every poi answers in time ends complete`() {
        runTest {
            val items = stayCheckController().stayAnswers(stayRequest(listOf(1L, RATE_LIMITED_POI_ID, 2L))).toList()

            assertEquals(StayStreamItem.Done(StayCheckOutcome.COMPLETE), items.last())
            assertEquals(1, items.count { it is StayStreamItem.Done }, "exactly one terminal item")
            assertEquals(3, items.filterIsInstance<StayStreamItem.Answer>().size)
        }
    }

    @Test
    fun `a poi that outlives only its own timeout does not make the check deadline_reached`() {
        runTest {
            // The slow POI times out on its own clock, well inside a generous stream deadline:
            // that is an answer the campground gave, not a check cut short.
            val items =
                stayCheckController(perPoiTimeout = Duration.ofMillis(TEST_PER_POI_TIMEOUT_MS))
                    .stayAnswers(stayRequest(listOf(SLOW_POI_ID, 2L)))
                    .toList()

            assertEquals(StayOutcome.TIMED_OUT, items.answers().single { it.poiId == SLOW_POI_ID }.outcome)
            assertEquals(StayStreamItem.Done(StayCheckOutcome.COMPLETE), items.last())
        }
    }

    @Test
    fun `a check that hits the stream deadline ends deadline_reached`() {
        runTest {
            val items =
                stayCheckController(streamTimeout = Duration.ofMillis(TEST_STREAM_TIMEOUT_MS))
                    .stayAnswers(stayRequest(listOf(SLOW_POI_ID, 2L)))
                    .toList()

            // Every poi still answers — the stalled one as timed_out — yet the check says it was cut short.
            val answers = items.answers()
            assertEquals(setOf(SLOW_POI_ID, 2L), answers.map { it.poiId }.toSet())
            assertEquals(StayOutcome.TIMED_OUT, answers.single { it.poiId == SLOW_POI_ID }.outcome)
            assertEquals(StayOutcome.ANSWERED, answers.single { it.poiId == 2L }.outcome)
            assertEquals(StayStreamItem.Done(StayCheckOutcome.DEADLINE_REACHED), items.last())
            assertEquals(1, items.count { it is StayStreamItem.Done }, "exactly one terminal item")
        }
    }

    @Test
    fun `a read that finishes after the stream deadline sends nothing`() {
        runTest {
            val blockedReads = BlockedReadLog()
            val items = mutableListOf<StayStreamItem>()
            val stream =
                launch {
                    stayCheckController(streamTimeout = Duration.ofMillis(TEST_STREAM_TIMEOUT_MS), blockedReads = blockedReads)
                        .stayAnswers(stayRequest(listOf(BLOCKED_POI_ID, 2L)))
                        .collect { items += it }
                }

            advanceTimeBy(TEST_STREAM_TIMEOUT_MS + 1)
            runCurrent()
            assertTrue(stream.isCompleted, "the stream ended at its deadline")
            assertEquals(0, blockedReads.returned, "the blocked read is still blocked at the deadline")
            val atDone = items.toList()
            assertEquals(StayStreamItem.Done(StayCheckOutcome.DEADLINE_REACHED), atDone.last())
            assertEquals(StayOutcome.TIMED_OUT, atDone.answers().single { it.poiId == BLOCKED_POI_ID }.outcome)

            // Release the read well after the deadline. It returns a real slice, which would
            // answer the poi a second time — as answered — if the stream let it.
            advanceTimeBy(BLOCKED_READ_MS)
            runCurrent()

            assertEquals(1, blockedReads.returned, "the read was released after the deadline, not skipped")
            assertEquals(atDone, items, "nothing follows done")
            assertEquals(1, items.answers().count { it.poiId == BLOCKED_POI_ID }, "one answer for the poi that was cut off")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a read that cannot be cancelled does not hold the stream past its deadline`() {
        runTest {
            val answers =
                stayCheckController(streamTimeout = Duration.ofMillis(TEST_STREAM_TIMEOUT_MS))
                    .stayAnswers(stayRequest(listOf(STUCK_POI_ID, 2L)))
                    .answers()

            assertEquals(StayOutcome.TIMED_OUT, answers.single { it.poiId == STUCK_POI_ID }.outcome)
            assertEquals(StayOutcome.ANSWERED, answers.single { it.poiId == 2L }.outcome)
            assertEquals(TEST_STREAM_TIMEOUT_MS, currentTime, "the stream ended at its deadline, not when the read did")
        }
    }

    @Test
    fun `a read that ends without an answer still answers, as the unclassified failure`() {
        runTest {
            val items = stayCheckController().stayAnswers(stayRequest(listOf(STRAY_CANCELLATION_POI_ID, 2L))).toList()
            val answers = items.answers()

            assertEquals(setOf(STRAY_CANCELLATION_POI_ID, 2L), answers.map { it.poiId }.toSet())
            val unanswered = answers.single { it.poiId == STRAY_CANCELLATION_POI_ID }
            assertEquals(StayOutcome.FAILED, unanswered.outcome)
            assertEquals(StayFailureReason.UNKNOWN, unanswered.reason)
            assertEquals(false, unanswered.retryable)
            // The stream did not run out of time, so the check is complete, not deadline_reached.
            assertEquals(StayStreamItem.Done(StayCheckOutcome.COMPLETE), items.last())
        }
    }

    @Test
    fun `the stay stream never runs more pois at once than the configured fan out`() {
        runTest {
            val meter = ConcurrencyMeter()
            stayCheckController(fanOutConcurrency = 2, meter = meter).stayAnswers(stayRequest((1L..8L).toList())).answers()
            assertEquals(2, meter.peak)
        }
    }

    @Test
    fun `a group size leaves out the campsites too small for it`() {
        runTest {
            // POI 1's long-run site sleeps 2; the other two state no capacity.
            val stay =
                stayCheckController()
                    .stayAnswers(stayRequest(listOf(1L), groupSize = 4))
                    .answers()
                    .single()
                    .stay
            assertEquals(2, assertNotNull(stay).checkedSites)
        }
    }

    @Test
    fun `a stay check reads every poi against now minus the max age the request carries`() {
        runTest {
            val now = Instant.parse("2026-10-12T12:00:00Z")
            val maxAge = Duration.ofSeconds(STAY_MAX_AGE_SECONDS)
            val recorder = CutoffRecorder()
            val controller =
                StayCheckController(
                    sliceLookup = FakePoiAvailabilitySliceLookup(recorder),
                    config =
                        StayCheckConfig(
                            maxPois = TEST_MAX_POIS,
                            fanOutConcurrency = TEST_FAN_OUT_CONCURRENCY,
                            // Neither end of the range is the request's age, so the cutoff can only come from the request.
                            defaultMaxAge = Duration.ofHours(2),
                            ipPoiLimitPerMinute = TEST_IP_POI_LIMIT_PER_MINUTE,
                            minMaxAge = Duration.ofMinutes(1),
                        ),
                    clock = Clock.fixed(now, ZoneOffset.UTC),
                )

            controller.stayAnswers(stayRequest(listOf(1L, 2L, 3L), maxAge = maxAge)).toList()

            assertEquals(List(3) { now.minus(maxAge) }, recorder.cutoffs)
        }
    }

    @Test
    fun `every poi in one stay check is measured against the same freshness cutoff`() {
        runTest {
            val recorder = CutoffRecorder()
            stayCheckController(recorder = recorder).stayAnswers(stayRequest(listOf(1L, 2L, 3L))).answers()
            assertEquals(1, recorder.cutoffs.distinct().size)
        }
    }
}

private fun stayCheckController(
    fanOutConcurrency: Int = TEST_FAN_OUT_CONCURRENCY,
    recorder: CutoffRecorder? = null,
    meter: ConcurrencyMeter? = null,
    perPoiTimeout: Duration = Duration.ofSeconds(TEST_GENEROUS_TIMEOUT_SECONDS),
    streamTimeout: Duration = Duration.ofSeconds(TEST_GENEROUS_TIMEOUT_SECONDS),
    blockedReads: BlockedReadLog? = null,
): StayCheckController =
    StayCheckController(
        sliceLookup = FakePoiAvailabilitySliceLookup(recorder, meter, blockedReads),
        config =
            StayCheckConfig(
                maxPois = TEST_MAX_POIS,
                fanOutConcurrency = fanOutConcurrency,
                defaultMaxAge = Duration.ZERO,
                ipPoiLimitPerMinute = TEST_IP_POI_LIMIT_PER_MINUTE,
                perPoiTimeout = perPoiTimeout,
                streamTimeout = streamTimeout,
            ),
        clock = Clock.systemUTC(),
    )

/** The answers in a stay stream's items, in the order they arrived. */
private fun List<StayStreamItem>.answers(): List<StayAnswerDto> = filterIsInstance<StayStreamItem.Answer>().map { it.answer }

/** The stream's answers; its terminal item is checked by the tests that care about it. */
private suspend fun Flow<StayStreamItem>.answers(): List<StayAnswerDto> = toList().answers()

private fun stayRequest(
    poiIds: List<Long>,
    groupSize: Int? = null,
    maxAge: Duration = Duration.ZERO,
): StayCheckRequest =
    StayCheckRequest(
        poiIds = poiIds,
        startDate = windowStart,
        endDate = windowEnd,
        siteTypes = emptyList(),
        groupSize = groupSize,
        maxAge = maxAge,
    )

/** Counts blocked reads that came back with a slice, so a test proves it released one rather than assuming it did. */
private class BlockedReadLog {
    var returned = 0
}

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
    private val blockedReads: BlockedReadLog? = null,
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
            BEYOND_HORIZON_POI_ID -> throw AvailabilityServiceError.BadDateWindow.BeyondBookingHorizon(windowLatest)
            CRASHING_POI_ID -> throw RuntimeException("boom: unmapped failure")
            // A cancellation nobody asked for — what a provider's own `withTimeout` throws.
            STRAY_CANCELLATION_POI_ID -> throw CancellationException("not this read's caller")
            // A blocking call in progress: nothing the caller does can cut it short.
            STUCK_POI_ID -> {
                withContext(NonCancellable) { delay(STUCK_READ_MS) }
                simpleSlice(poiId)
            }
            // A read blocked in a call that, once it ends, returns a slice whatever became of
            // its caller meanwhile — a JDBC wait, say: the cancellation it missed is not thrown.
            BLOCKED_POI_ID -> {
                try {
                    withContext(NonCancellable) { delay(BLOCKED_READ_MS) }
                } catch (_: CancellationException) {
                    // Raised on the way out of the blocked call: the read is already cancelled.
                }
                blockedReads?.let { it.returned += 1 }
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

/** No campsite matched the requested site types: resolved, but nothing to check. */
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
