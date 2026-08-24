package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.BulkAvailabilityConfig
import ca.floo.roadtrip.model.api.BulkAvailabilityResponseDto
import ca.floo.roadtrip.model.api.BulkPoiAvailabilityDto
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.service.api.availabilityResponseFromObservations
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
    val siteTypes: List<String>,
)

/**
 * The one [CampsiteAvailabilityController] method this controller fans out
 * over. Extracted so tests can fake per-POI outcomes without a database;
 * [CampsiteAvailabilityController.poiAvailabilitySlice] satisfies it directly.
 */
internal fun interface PoiAvailabilitySliceLookup {
    suspend fun poiAvailabilitySlice(
        poiId: Long,
        siteTypes: List<String>,
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
    ) : this(PoiAvailabilitySliceLookup(campsiteController::poiAvailabilitySlice), config, clock)

    suspend fun availabilityForPois(request: BulkAvailabilityRequest): BulkAvailabilityResponseDto {
        val freshAtOrAfter = Instant.now(clock).minus(config.tolerance)
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
            rank(
                sliceLookup.poiAvailabilitySlice(
                    poiId = poiId,
                    siteTypes = request.siteTypes,
                    startDate = request.startDate,
                    endDate = request.endDate,
                    freshAtOrAfter = freshAtOrAfter,
                ),
                request.minNights,
            )
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

    private fun rank(
        slice: PoiAvailabilitySlice,
        minNights: Int,
    ): BulkPoiAvailabilityDto {
        val batch = slice.batch
        val campsites =
            if (batch == null) {
                emptyList()
            } else {
                slice.campsites
                    .map { campsite ->
                        val forCampsite = batch.observations.filter { it.campsiteId == campsite.id }
                        val response =
                            availabilityResponseFromObservations(
                                batch.copy(
                                    observations = forCampsite,
                                    campsiteId = campsite.id,
                                    startDate = slice.startDate,
                                    endDate = slice.endDate,
                                ),
                            )
                        response.copy(longestRunNights = longestRunNights(forCampsite))
                    }.filter { (it.longestRunNights ?: 0) >= minNights }
                    .sortedByDescending { it.longestRunNights ?: 0 }
            }

        return BulkPoiAvailabilityDto(
            poiId = slice.poiId,
            startDate = slice.startDate.toString(),
            endDate = slice.endDate.toString(),
            campsites = campsites,
        )
    }
}
