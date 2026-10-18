package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.StayCheckConfig
import ca.floo.roadtrip.model.api.StayAnswerDto
import ca.floo.roadtrip.model.api.StayCheckOutcome
import ca.floo.roadtrip.model.api.StayOutcome
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.domain.CampsiteKind
import ca.floo.roadtrip.support.causeChain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.toKotlinDuration

private val log = LoggerFactory.getLogger("StayCheckController")

/**
 * The answer a campground gives when it outlives [StayCheckConfig.perPoiTimeout],
 * or is still waiting when the stream reaches [StayCheckConfig.streamTimeout].
 */
internal const val STAY_TIMEOUT_ERROR = "timeout"

/** A stay to check at many campgrounds, as the stream answers it. */
internal data class StayCheckRequest(
    val poiIds: List<Long>,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val siteTypes: List<CampsiteKind>,
    /** Campsites that sleep fewer are left out; null leaves capacity unchecked. */
    val groupSize: Int? = null,
    /**
     * Answer only from observations this old or younger. Already clamped by the route
     * into the config's range, so the controller takes it as given.
     */
    val maxAge: Duration,
)

/**
 * The one [CampsiteAvailabilityController] method this controller fans out
 * over. Extracted so tests can fake per-POI outcomes without a database;
 * [CampsiteAvailabilityController.poiAvailabilitySlice] satisfies it directly.
 */
internal fun interface PoiAvailabilitySliceLookup {
    suspend fun poiAvailabilitySlice(
        poiId: Long,
        siteTypes: List<CampsiteKind>,
        startDate: LocalDate?,
        endDate: LocalDate?,
        freshAtOrAfter: Instant?,
    ): PoiAvailabilitySlice
}

/** What every POI of one request is read over: the same campsites, the same window, the same freshness. */
private data class SliceQuery(
    val siteTypes: List<CampsiteKind>,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val groupSize: Int?,
    val freshAtOrAfter: Instant,
)

/** One POI's read: its slice narrowed to the campsites that fit the group, or the code it failed with. */
private sealed interface PoiRead {
    data class Resolved(
        val slice: PoiAvailabilitySlice,
    ) : PoiRead

    data class Failed(
        val error: String,
    ) : PoiRead
}

/**
 * The stay check ([stayAnswers]): one stay read across many POIs, over the same
 * per-POI slice the detail endpoint uses so they cannot drift, and answered as a
 * stream. Each POI's failure is captured at the fan-out boundary, so one bad
 * vendor never blanks the rest.
 */
internal class StayCheckController(
    private val sliceLookup: PoiAvailabilitySliceLookup,
    private val config: StayCheckConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    constructor(
        campsiteController: CampsiteAvailabilityController,
        config: StayCheckConfig,
        clock: Clock = Clock.systemUTC(),
    ) : this(
        PoiAvailabilitySliceLookup(campsiteController::poiAvailabilitySlice),
        config,
        clock,
    )

    /**
     * One answer per requested POI, each as it lands, and never fewer. POIs start
     * in request order — the caller sends the nearest first — at most
     * [StayCheckConfig.fanOutConcurrency] at a time. One that outlives
     * [StayCheckConfig.perPoiTimeout] answers [STAY_TIMEOUT_ERROR] — outcome
     * `timed_out` — and once the whole stream reaches [StayCheckConfig.streamTimeout]
     * so does every POI yet to answer; a read that ended without an answer at all
     * answers the unclassified failure. Each failure is classified by [stayFailureOf]
     * into the answer's outcome, reason and retryability. So the flow completes by
     * its deadline, with every POI in it. Cancelling the collector cancels every
     * read in flight.
     *
     * The flow ends with exactly one [StayStreamItem.Done], after every answer: it
     * says [StayCheckOutcome.DEADLINE_REACHED] when the stream's deadline fired, and
     * [StayCheckOutcome.COMPLETE] otherwise. That is the same decision the sweep
     * below makes to pick its error code, emitted once rather than derived a second
     * way — every POI answers either way, so only the server can say which answers
     * are real. A read that merely ended without a result still completes the check:
     * the stream did not run out of time. Nothing follows `Done`: the sweep leaves
     * no POI unanswered, so a late read finds its answer already given.
     *
     * The reads are deliberately not the flow's children. A per-POI timeout lands
     * at a read's next suspension, so a read stuck in a blocking call — a database
     * lock wait — outlives it; as a child it would hold the stream open with it.
     * Instead the stream answers for it at the deadline and ends, and the read,
     * cancelled, finishes on its own, its late answer dropped.
     */
    fun stayAnswers(request: StayCheckRequest): Flow<StayStreamItem> =
        channelFlow {
            val query = SliceQuery(request.siteTypes, request.startDate, request.endDate, request.groupSize, cutoffFor(request.maxAge))
            val gate = Semaphore(config.fanOutConcurrency)
            val perPoiTimeout = config.perPoiTimeout.toKotlinDuration()
            val answered = ConcurrentHashMap.newKeySet<Int>()

            // Exactly once per POI, whoever gets there first: its read, or the sweep below.
            // The channel is unbounded, so the send never suspends and never fails while open.
            fun answer(
                index: Int,
                answer: StayAnswerDto,
            ) {
                if (answered.add(index)) trySend(StayStreamItem.Answer(answer))
            }

            val reads = SupervisorJob()
            val readScope = CoroutineScope(coroutineContext.minusKey(Job) + reads)
            try {
                request.poiIds.forEachIndexed { index, poiId ->
                    readScope.launch {
                        val read =
                            gate.withPermit { withTimeoutOrNull(perPoiTimeout) { read(poiId, query) } }
                                ?: PoiRead.Failed(STAY_TIMEOUT_ERROR)
                        answer(index, answerOf(poiId, read))
                    }
                }
                val finished = withTimeoutOrNull(config.streamTimeout.toKotlinDuration()) { reads.children.toList().joinAll() }
                // Whoever has not answered still does: past the deadline it ran out of
                // time; otherwise its read ended without a result (a stray cancellation).
                val unanswered = if (finished == null) STAY_TIMEOUT_ERROR else AvailabilityProviderError.Unknown.CODE
                request.poiIds.forEachIndexed { index, poiId -> answer(index, answerOf(poiId, PoiRead.Failed(unanswered))) }
                trySend(StayStreamItem.Done(if (finished == null) StayCheckOutcome.DEADLINE_REACHED else StayCheckOutcome.COMPLETE))
            } finally {
                reads.cancel()
            }
        }.buffer(Channel.UNLIMITED)

    private fun answerOf(
        poiId: Long,
        read: PoiRead,
    ): StayAnswerDto =
        when (read) {
            is PoiRead.Resolved -> StayAnswerDto(poiId = poiId, outcome = StayOutcome.ANSWERED, stay = read.slice.staySummary())
            is PoiRead.Failed ->
                stayFailureOf(read.error).let { failure ->
                    StayAnswerDto(poiId = poiId, outcome = failure.outcome, reason = failure.reason, retryable = failure.retryable)
                }
        }

    /** The oldest instant an observation may carry and still answer: [maxAge] before now. */
    private fun cutoffFor(maxAge: Duration): Instant = Instant.now(clock).minus(maxAge)

    private suspend fun read(
        poiId: Long,
        query: SliceQuery,
    ): PoiRead =
        try {
            val slice = sliceLookup.poiAvailabilitySlice(poiId, query.siteTypes, query.startDate, query.endDate, query.freshAtOrAfter)
            // Narrowed once, here, so the read answers over the campsites that fit the group.
            PoiRead.Resolved(slice.copy(campsites = slice.campsites.fittingGroup(query.groupSize)))
        } catch (e: CancellationException) {
            // Coroutine cancellation (a sibling's failure, the caller disconnecting,
            // a per-POI timeout) must propagate, not be reported as this POI's error.
            throw e
        } catch (e: AvailabilityServiceError) {
            PoiRead.Failed(e.error)
        } catch (e: AvailabilityProviderError) {
            PoiRead.Failed(e.code)
        } catch (e: Exception) {
            // Anything unmapped (e.g. a DB connection-acquisition timeout) must not
            // escape the fan-out and cancel every sibling POI: a failure is that POI's answer,
            // not the stream's.
            log.error("stay check poi={} failed: {}", poiId, causeChain(e), e)
            PoiRead.Failed(AvailabilityProviderError.Unknown(e).code)
        }
}
