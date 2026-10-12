package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.BulkAvailabilityConfig
import ca.floo.roadtrip.model.api.BulkAvailabilityResponseDto
import ca.floo.roadtrip.model.api.BulkAvailabilityView
import ca.floo.roadtrip.model.api.BulkPoiAvailabilityDto
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.domain.CampsiteKind
import ca.floo.roadtrip.support.causeChain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

private val log = LoggerFactory.getLogger("BulkAvailabilityController")

internal data class BulkAvailabilityRequest(
    val poiIds: List<Long>,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val minNights: Int,
    val siteTypes: List<CampsiteKind>,
    /** Campsites that sleep fewer are left out; null leaves capacity unchecked. */
    val groupSize: Int? = null,
    val view: BulkAvailabilityView = BulkAvailabilityView.CAMPSITES,
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

/**
 * Bulk read across many POIs. Fans out over the same per-POI slice the detail
 * endpoint uses, so the two cannot drift, and captures each POI's failure at
 * the fan-out boundary so one bad vendor never blanks the scan.
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
        val freshAtOrAfter = Instant.now(clock).minus(if (request.fresh) config.freshTolerance else config.tolerance)
        val gate = Semaphore(config.fanOutConcurrency)

        val entries =
            coroutineScope {
                request.poiIds
                    .map { poiId -> async { gate.withPermit { entryFor(poiId, request, freshAtOrAfter) } } }
                    .awaitAll()
            }
        return BulkAvailabilityResponseDto(pois = entries)
    }

    private suspend fun entryFor(
        poiId: Long,
        request: BulkAvailabilityRequest,
        freshAtOrAfter: Instant,
    ): BulkPoiAvailabilityDto =
        try {
            val slice =
                sliceLookup.poiAvailabilitySlice(
                    poiId = poiId,
                    siteTypes = request.siteTypes,
                    startDate = request.startDate,
                    endDate = request.endDate,
                    freshAtOrAfter = freshAtOrAfter,
                )
            // Narrowed once, here, so both views answer over the same campsites.
            val fitting = slice.copy(campsites = slice.campsites.fittingGroup(request.groupSize))
            when (request.view) {
                BulkAvailabilityView.CAMPSITES -> rank(fitting, request.minNights)
                BulkAvailabilityView.SUMMARY -> summarize(fitting)
            }
        } catch (e: CancellationException) {
            // Coroutine cancellation (e.g. a sibling POI's failure, or the caller
            // disconnecting) must propagate, not be reported as this POI's error.
            throw e
        } catch (e: AvailabilityServiceError) {
            BulkPoiAvailabilityDto(poiId = poiId, error = e.error)
        } catch (e: AvailabilityProviderError) {
            BulkPoiAvailabilityDto(poiId = poiId, error = e.code)
        } catch (e: Exception) {
            // Anything unmapped (e.g. a DB connection-acquisition timeout) must not
            // escape the fan-out and cancel every sibling POI. See spec Decision 6.
            log.error("bulk availability poi={} failed: {}", poiId, causeChain(e), e)
            BulkPoiAvailabilityDto(poiId = poiId, error = AvailabilityProviderError.Unknown(e).code)
        }

    private fun summarize(slice: PoiAvailabilitySlice): BulkPoiAvailabilityDto =
        BulkPoiAvailabilityDto(
            poiId = slice.poiId,
            startDate = slice.startDate.toString(),
            endDate = slice.endDate.toString(),
            summary = slice.staySummary(),
        )

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
