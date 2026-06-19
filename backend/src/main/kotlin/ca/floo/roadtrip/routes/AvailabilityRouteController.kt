package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.api.BulkAvailEntrySchema
import ca.floo.roadtrip.models.api.BulkAvailResponseSchema
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableType
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import ca.floo.roadtrip.service.api.AvailabilityStatus
import ca.floo.roadtrip.service.api.PoiReservablesAvailabilityResponseDto
import ca.floo.roadtrip.service.availability.AvailabilityService
import ca.floo.roadtrip.service.availability.AvailabilityServiceError
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private val availabilityRouteControllerLog = LoggerFactory.getLogger("AvailabilityRouteController")

private const val MAX_BULK_WINDOW_DAYS = 14
private const val EMPTY_WINDOW_DEFAULT_DAYS: Long = 7

internal class AvailabilityRouteController(
    private val providerRefs: CampsiteProviderRepo,
    private val reservablesRepo: ReservableRepo,
    private val availabilityService: AvailabilityService,
) {
    suspend fun poiReservablesAvailability(
        poiId: Long,
        startDate: LocalDate?,
        endDate: LocalDate?,
        force: Boolean,
        siteTypes: List<String>,
    ): PoiReservablesAvailabilityResponseDto {
        val reservables =
            reservablesRepo
                .findByPoi(poiId, ReservableType.SITE)
                .filterAvailabilitySiteTypes(siteTypes)
        if (reservables.isEmpty()) {
            if (!providerRefs.campgroundExists(poiId)) {
                throw AvailabilityServiceError.NotFound
            }
            val (start, end) = displayWindow(startDate, endDate)
            return PoiReservablesAvailabilityResponseDto(
                poiId = poiId,
                startDate = start.toString(),
                endDate = end.toString(),
                reservables = emptyList(),
            )
        }

        val availability =
            availabilityService.getByRids(
                rids = reservables.map { it.rid },
                startDate = startDate,
                endDate = endDate,
                force = force,
            )
        val (fallbackStart, fallbackEnd) = displayWindow(startDate, endDate)
        return PoiReservablesAvailabilityResponseDto(
            poiId = poiId,
            startDate = availability.firstOrNull()?.startDate ?: fallbackStart.toString(),
            endDate = availability.firstOrNull()?.endDate ?: fallbackEnd.toString(),
            reservables = availability,
        )
    }

    suspend fun bulkAvailability(
        ids: List<Long>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): BulkAvailResponseSchema {
        if (!bulkWindowIsValid(startDate, endDate)) {
            throw AvailabilityServiceError.BadDateWindow
        }
        val results =
            coroutineScope {
                ids
                    .map { id ->
                        async {
                            fetchOneBulk(
                                poiId = id,
                                startDate = startDate,
                                endDate = endDate,
                            )
                        }
                    }.awaitAll()
            }
        return BulkAvailResponseSchema(
            startDate = startDate.toString(),
            endDate = endDate.toString(),
            results = results,
        )
    }

    private suspend fun fetchOneBulk(
        poiId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
    ): BulkAvailEntrySchema {
        if (providerRefs.findProviderRef(poiId) == null) {
            return BulkAvailEntrySchema(id = poiId, status = 404, available_dates = emptyList())
        }
        val rids = reservablesRepo.findByPoi(poiId, ReservableType.SITE).map { it.rid }
        if (rids.isEmpty()) {
            return BulkAvailEntrySchema(id = poiId, status = 200, available_dates = emptyList())
        }

        return try {
            val availability =
                availabilityService.getByRids(
                    rids = rids,
                    startDate = startDate,
                    endDate = endDate,
                    force = false,
                )
            BulkAvailEntrySchema(id = poiId, status = 200, available_dates = availableDates(availability))
        } catch (e: AvailabilityServiceError) {
            BulkAvailEntrySchema(id = poiId, status = httpStatusFor(e), available_dates = emptyList())
        } catch (e: ReservationProviderError) {
            availabilityRouteControllerLog.info("bulk availability poi={} failed: {}", poiId, e.message)
            BulkAvailEntrySchema(id = poiId, status = httpStatusFor(e), available_dates = emptyList())
        }
    }
}

private fun List<Reservable>.filterAvailabilitySiteTypes(siteTypes: Collection<String>): List<Reservable> {
    if (siteTypes.isEmpty()) return this
    val allowed = siteTypes.toSet()
    return filter { it.siteType != null && it.siteType in allowed }
}

private fun availableDates(responses: List<AvailabilityResponseDto>): List<String> =
    responses
        .flatMap { response ->
            response.availability
                .filter { it.status == AvailabilityStatus.AVAILABLE }
                .map { it.date }
        }.distinct()
        .sorted()

private fun displayWindow(
    startDate: LocalDate?,
    endDate: LocalDate?,
): Pair<LocalDate, LocalDate> {
    val start = startDate ?: LocalDate.now(ZoneId.systemDefault())
    val end = endDate ?: start.plusDays(EMPTY_WINDOW_DEFAULT_DAYS)
    if (!end.isAfter(start)) throw AvailabilityServiceError.BadDateWindow
    return start to end
}

private fun bulkWindowIsValid(
    startDate: LocalDate,
    endDate: LocalDate,
): Boolean =
    ChronoUnit.DAYS
        .between(startDate, endDate)
        .toInt() in 1..MAX_BULK_WINDOW_DAYS

private fun httpStatusFor(e: AvailabilityServiceError): Int =
    when (e) {
        AvailabilityServiceError.BadDateWindow -> 400
        AvailabilityServiceError.NotFound -> 404
        AvailabilityServiceError.UnknownCampground -> 404
    }

private fun httpStatusFor(e: ReservationProviderError): Int =
    when (e) {
        is ReservationProviderError.RateLimited -> 429
        is ReservationProviderError.Unsupported -> 422
        is ReservationProviderError.WrongRefType -> 500
        else -> 503
    }
