package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.api.PoiReservablesAvailabilityResponseDto
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableType
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.api.availabilityResponseFromObservations
import ca.floo.roadtrip.service.reservation.AvailabilityRequest
import ca.floo.roadtrip.service.reservation.ProviderRefParser
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistry
import java.time.LocalDate

private const val EMPTY_WINDOW_DEFAULT_DAYS = 7
private const val EMPTY_WINDOW_MAX_DAYS = 60
private const val EMPTY_WINDOW_HORIZON_DAYS = 365
private const val PROVIDER_WINDOW_DEFAULT_DAYS = 7
private const val PROVIDER_WINDOW_MAX_DAYS = 60

internal class AvailabilityServiceImpl(
    private val providerRefs: CampsiteProviderRepo,
    private val reservablesRepo: ReservableRepo,
    private val composer: ReservableAvailabilityComposer,
    private val dateResolver: AvailabilityDateResolver,
    private val reservationProviders: ReservationProviderRegistry,
) : AvailabilityService {
    override suspend fun poiReservablesAvailability(
        poiId: Long,
        startDate: LocalDate?,
        endDate: LocalDate?,
        siteTypes: List<String>,
    ): PoiReservablesAvailabilityResponseDto {
        val reservables =
            reservablesRepo
                .findByPoi(poiId, ReservableType.SITE)
                .filterAvailabilitySiteTypes(siteTypes)
        if (reservables.isEmpty()) {
            return cataloglessProviderAvailability(
                poiId = poiId,
                startDate = startDate,
                endDate = endDate,
                siteTypes = siteTypes,
                providerRefs = providerRefs,
                reservationProviders = reservationProviders,
                dateResolver = dateResolver,
            )
        }

        val availability =
            composer.availabilityFor(
                reservables = reservables,
                startDate = startDate,
                endDate = endDate,
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
}

private suspend fun cataloglessProviderAvailability(
    poiId: Long,
    startDate: LocalDate?,
    endDate: LocalDate?,
    siteTypes: List<String>,
    providerRefs: CampsiteProviderRepo,
    reservationProviders: ReservationProviderRegistry,
    dateResolver: AvailabilityDateResolver,
): PoiReservablesAvailabilityResponseDto {
    val row = providerRefs.findProviderRef(poiId)
    if (row == null || siteTypes.isNotEmpty()) {
        // `site_type` filters apply to local catalog rows. Catalogless provider
        // fallback has upstream site ids only, so returning empty is explicit:
        // the caller asked for a catalog classification we cannot prove.
        val (start, end) = displayWindow(poiId, startDate, endDate, providerRefs, dateResolver)
        return emptyPoiAvailability(poiId, start, end)
    }

    val provider = reservationProviders.forPoi(row)
    val ref = ProviderRefParser.parse(row.providerRefJson)
    if (provider == null || ref == null || !provider.capabilities.supportsAvailability) {
        val (start, end) = displayWindow(poiId, startDate, endDate, providerRefs, dateResolver)
        return emptyPoiAvailability(poiId, start, end)
    }

    val dateContext = dateResolver.context(lat = row.lat, lng = row.lng)
    val window =
        dateResolver.resolveWindow(
            startDate = startDate,
            endDate = endDate,
            context = dateContext,
            bookingHorizonDays = provider.capabilities.bookingHorizonDays,
            maxDays = PROVIDER_WINDOW_MAX_DAYS,
            defaultDays = PROVIDER_WINDOW_DEFAULT_DAYS,
        )
    val batch =
        provider.availability(
            AvailabilityRequest(
                ref = ref,
                startDate = window.startDate,
                endDate = window.endDate,
            ),
        )
    // Render-only fallback for POIs without linked reservables. Availability
    // persistence currently happens through AvailabilityLoader once catalog
    // rows exist; synthetic upstream ids are not persisted here.
    val byReservableId =
        batch.observations
            .groupBy { it.reservableId }
            .toSortedMap()
    return PoiReservablesAvailabilityResponseDto(
        poiId = poiId,
        startDate = batch.startDate.toString(),
        endDate = batch.endDate.toString(),
        reservables =
            byReservableId.map { (rid, observations) ->
                availabilityResponseFromObservations(
                    batch.copy(
                        observations = observations,
                        reservableId = rid,
                    ),
                )
            },
    )
}

private fun emptyPoiAvailability(
    poiId: Long,
    startDate: LocalDate,
    endDate: LocalDate,
): PoiReservablesAvailabilityResponseDto =
    PoiReservablesAvailabilityResponseDto(
        poiId = poiId,
        startDate = startDate.toString(),
        endDate = endDate.toString(),
        reservables = emptyList(),
    )

private fun List<Reservable>.filterAvailabilitySiteTypes(siteTypes: Collection<String>): List<Reservable> {
    if (siteTypes.isEmpty()) return this
    val allowed = siteTypes.toSet()
    return filter { it.siteType != null && it.siteType in allowed }
}

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
