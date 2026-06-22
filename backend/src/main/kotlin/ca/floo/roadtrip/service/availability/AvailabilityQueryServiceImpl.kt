package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.api.AvailabilityResponseDto
import ca.floo.roadtrip.models.api.BulkAvailabilityEntryDto
import ca.floo.roadtrip.models.api.BulkAvailabilityResponseDto
import ca.floo.roadtrip.models.api.PoiReservablesAvailabilityResponseDto
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableType
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val availabilityQueryServiceLog = LoggerFactory.getLogger("AvailabilityQueryService")

private const val MAX_BULK_WINDOW_DAYS = 14
private const val EMPTY_WINDOW_DEFAULT_DAYS = 7
private const val EMPTY_WINDOW_MAX_DAYS = 60
private const val EMPTY_WINDOW_HORIZON_DAYS = 365

internal class AvailabilityQueryServiceImpl(
    private val providerRefs: CampsiteProviderRepo,
    private val reservablesRepo: ReservableRepo,
    private val availabilityService: AvailabilityService,
    private val dateResolver: AvailabilityDateResolver,
) : AvailabilityQueryService {
    override suspend fun poiReservablesAvailability(
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
            val (start, end) = displayWindow(poiId, startDate, endDate, providerRefs, dateResolver)
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
        val firstAvailability = availability.firstOrNull()
        if (firstAvailability != null) {
            return PoiReservablesAvailabilityResponseDto(
                poiId = poiId,
                startDate = firstAvailability.startDate,
                endDate = firstAvailability.endDate,
                reservables = availability,
            )
        }

        val (fallbackStart, fallbackEnd) = displayWindow(poiId, startDate, endDate, providerRefs, dateResolver)
        return PoiReservablesAvailabilityResponseDto(
            poiId = poiId,
            startDate = fallbackStart.toString(),
            endDate = fallbackEnd.toString(),
            reservables = availability,
        )
    }

    override suspend fun bulkAvailability(
        ids: List<Long>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): BulkAvailabilityResponseDto {
        validateBulkWindow(startDate, endDate)
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
        return BulkAvailabilityResponseDto(
            startDate = startDate.toString(),
            endDate = endDate.toString(),
            results = results,
        )
    }

    private suspend fun fetchOneBulk(
        poiId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
    ): BulkAvailabilityEntryDto {
        if (!providerRefs.campgroundExists(poiId)) {
            return BulkAvailabilityEntryDto(id = poiId, status = 404, availableDates = emptyList())
        }
        val rids = reservablesRepo.findByPoi(poiId, ReservableType.SITE).map { it.rid }
        if (rids.isEmpty()) {
            return BulkAvailabilityEntryDto(id = poiId, status = 200, availableDates = emptyList())
        }

        return try {
            val availability =
                availabilityService.getByRids(
                    rids = rids,
                    startDate = startDate,
                    endDate = endDate,
                    force = false,
                )
            BulkAvailabilityEntryDto(id = poiId, status = 200, availableDates = availableDates(availability))
        } catch (e: AvailabilityServiceError) {
            BulkAvailabilityEntryDto(id = poiId, status = httpStatusFor(e), availableDates = emptyList())
        } catch (e: ReservationProviderError) {
            availabilityQueryServiceLog.info("bulk availability poi={} failed: {}", poiId, e.message)
            BulkAvailabilityEntryDto(id = poiId, status = httpStatusFor(e), availableDates = emptyList())
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
    poiId: Long,
    startDate: LocalDate?,
    endDate: LocalDate?,
    providerRefs: CampsiteProviderRepo,
    dateResolver: AvailabilityDateResolver,
): Pair<LocalDate, LocalDate> {
    val row = providerRefs.findDateContext(poiId) ?: throw AvailabilityServiceError.NotFound
    val dateContext = dateResolver.context(lat = row.lat, lng = row.lng)
    val window =
        dateResolver.resolveWindow(
            startDate = startDate,
            endDate = endDate,
            context = dateContext,
            bookingHorizonDays = EMPTY_WINDOW_HORIZON_DAYS,
            maxDays = EMPTY_WINDOW_MAX_DAYS,
            defaultDays = EMPTY_WINDOW_DEFAULT_DAYS,
        )
    return window.startDate to window.endDate
}

private fun validateBulkWindow(
    startDate: LocalDate,
    endDate: LocalDate,
) {
    if (!endDate.isAfter(startDate)) throw AvailabilityServiceError.BadDateWindow.EndBeforeStart
    val days =
        ChronoUnit.DAYS
            .between(startDate, endDate)
            .toInt() in 1..MAX_BULK_WINDOW_DAYS
    if (!days) throw AvailabilityServiceError.BadDateWindow.WindowTooLong(maxDays = MAX_BULK_WINDOW_DAYS)
}

private fun httpStatusFor(e: AvailabilityServiceError): Int =
    when (e) {
        is AvailabilityServiceError.BadDateWindow -> 400
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
