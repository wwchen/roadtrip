package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.BulkAvailabilityConfig
import ca.floo.roadtrip.model.api.BulkAvailabilityResponseDto
import ca.floo.roadtrip.model.api.BulkPoiAvailabilityDto
import ca.floo.roadtrip.model.api.StayAnswerDto
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.domain.CampsiteKind
import ca.floo.roadtrip.support.causeChain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.toKotlinDuration

private val log = LoggerFactory.getLogger("BulkAvailabilityController")

/**
 * The answer a campground gives when it outlives [BulkAvailabilityConfig.perPoiTimeout],
 * or is still waiting when the stream reaches [BulkAvailabilityConfig.streamTimeout].
 */
internal const val STAY_TIMEOUT_ERROR = "timeout"

internal data class BulkAvailabilityRequest(
    val poiIds: List<Long>,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val minNights: Int,
    val siteTypes: List<CampsiteKind>,
    /** Campsites that sleep fewer are left out; null leaves capacity unchecked. */
    val groupSize: Int? = null,
    /** Answer only from observations [BulkAvailabilityConfig.freshTolerance] old or younger. */
    val fresh: Boolean = false,
)

/** A stay to check at many campgrounds, as the stream answers it. */
internal data class StayCheckRequest(
    val poiIds: List<Long>,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val siteTypes: List<CampsiteKind>,
    /** Campsites that sleep fewer are left out; null leaves capacity unchecked. */
    val groupSize: Int? = null,
    /** Answer only from observations [BulkAvailabilityConfig.freshTolerance] old or younger. */
    val fresh: Boolean = false,
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
 * Bulk reads across many POIs, over the same per-POI slice the detail endpoint
 * uses so they cannot drift: the per-campsite scan ([availabilityForPois]),
 * answered whole, and the stay check ([stayAnswers]), answered as a stream. Each
 * POI's failure is captured at the fan-out boundary, so one bad vendor never
 * blanks the rest.
 */
internal class BulkAvailabilityController(
    private val sliceLookup: PoiAvailabilitySliceLookup,
    private val config: BulkAvailabilityConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    constructor(
        campsiteController: CampsiteAvailabilityController,
        config: BulkAvailabilityConfig,
        clock: Clock = Clock.systemUTC(),
    ) : this(
        PoiAvailabilitySliceLookup(campsiteController::poiAvailabilitySlice),
        config,
        clock,
    )

    suspend fun availabilityForPois(request: BulkAvailabilityRequest): BulkAvailabilityResponseDto {
        val query = SliceQuery(request.siteTypes, request.startDate, request.endDate, request.groupSize, freshAtOrAfter(request.fresh))
        val gate = Semaphore(config.fanOutConcurrency)

        val entries =
            coroutineScope {
                request.poiIds
                    .map { poiId ->
                        async {
                            gate.withPermit {
                                when (val read = read(poiId, query)) {
                                    is PoiRead.Resolved -> rank(read.slice, request.minNights)
                                    is PoiRead.Failed -> BulkPoiAvailabilityDto(poiId = poiId, error = read.error)
                                }
                            }
                        }
                    }.awaitAll()
            }
        return BulkAvailabilityResponseDto(pois = entries)
    }

    /**
     * One answer per requested POI, each as it lands, and never fewer. POIs start
     * in request order — the caller sends the nearest first — at most
     * [BulkAvailabilityConfig.fanOutConcurrency] at a time. One that outlives
     * [BulkAvailabilityConfig.perPoiTimeout] answers [STAY_TIMEOUT_ERROR], and once
     * the whole stream reaches [BulkAvailabilityConfig.streamTimeout] so does every
     * POI yet to answer; a read that ended without an answer at all answers the
     * unclassified failure. So the flow always completes, with every POI in it.
     * Cancelling the collector cancels every read in flight.
     *
     * The timeouts land at a read's next suspension: a blocking database call in
     * progress finishes first, bounded by the pool's connection timeout and the
     * query itself.
     */
    fun stayAnswers(request: StayCheckRequest): Flow<StayAnswerDto> =
        channelFlow {
            val query = SliceQuery(request.siteTypes, request.startDate, request.endDate, request.groupSize, freshAtOrAfter(request.fresh))
            val gate = Semaphore(config.fanOutConcurrency)
            val perPoiTimeout = config.perPoiTimeout.toKotlinDuration()
            val answered = ConcurrentHashMap.newKeySet<Int>()
            val finished =
                withTimeoutOrNull(config.streamTimeout.toKotlinDuration()) {
                    coroutineScope {
                        request.poiIds.forEachIndexed { index, poiId ->
                            launch {
                                val read =
                                    gate.withPermit { withTimeoutOrNull(perPoiTimeout) { read(poiId, query) } }
                                        ?: PoiRead.Failed(STAY_TIMEOUT_ERROR)
                                // The channel is unbounded, so this never suspends: an answer
                                // sent is an answer marked, with no cancellation between.
                                send(answerOf(poiId, read))
                                answered += index
                            }
                        }
                    }
                }
            // Whoever has not answered still does: past the deadline it ran out of
            // time; otherwise its read ended without a result (a stray cancellation).
            val unanswered = if (finished == null) STAY_TIMEOUT_ERROR else AvailabilityProviderError.Unknown.CODE
            request.poiIds.forEachIndexed { index, poiId ->
                if (index !in answered) send(StayAnswerDto(poiId = poiId, error = unanswered))
            }
        }.buffer(Channel.UNLIMITED)

    private fun answerOf(
        poiId: Long,
        read: PoiRead,
    ): StayAnswerDto =
        when (read) {
            is PoiRead.Resolved -> StayAnswerDto(poiId = poiId, summary = read.slice.staySummary())
            is PoiRead.Failed -> StayAnswerDto(poiId = poiId, error = read.error)
        }

    private fun freshAtOrAfter(fresh: Boolean): Instant = Instant.now(clock).minus(if (fresh) config.freshTolerance else config.tolerance)

    private suspend fun read(
        poiId: Long,
        query: SliceQuery,
    ): PoiRead =
        try {
            val slice = sliceLookup.poiAvailabilitySlice(poiId, query.siteTypes, query.startDate, query.endDate, query.freshAtOrAfter)
            // Narrowed once, here, so both reads answer over the same campsites.
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
            // escape the fan-out and cancel every sibling POI. See spec Decision 6.
            log.error("bulk availability poi={} failed: {}", poiId, causeChain(e), e)
            PoiRead.Failed(AvailabilityProviderError.Unknown(e).code)
        }

    private fun rank(
        slice: PoiAvailabilitySlice,
        minNights: Int,
    ): BulkPoiAvailabilityDto {
        val campsites =
            slice
                .perCampsiteEnvelopes()
                .map { it.response.copy(longestRunNights = longestRunNights(it.observations)) }
                .filter { (it.longestRunNights ?: 0) >= minNights }
                .sortedByDescending { it.longestRunNights ?: 0 }

        return BulkPoiAvailabilityDto(
            poiId = slice.poiId,
            startDate = slice.startDate.toString(),
            endDate = slice.endDate.toString(),
            campsites = campsites,
        )
    }
}
