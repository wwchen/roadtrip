package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.api.PoiReservablesAvailabilityResponseDto
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableType
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.reservation.CapabilityLimit
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val EMPTY_WINDOW_DEFAULT_DAYS = 7
private const val EMPTY_WINDOW_MAX_DAYS = 60
private const val EMPTY_WINDOW_HORIZON_DAYS = 365

internal class AvailabilityServiceImpl(
    private val providerRefs: CampsiteProviderRepo,
    private val reservablesRepo: ReservableRepo,
    private val composer: ReservableAvailabilityComposer,
    private val dateResolver: AvailabilityDateResolver,
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
            // No linked catalog rows: report an empty availability window. POIs
            // whose provider has no importable catalog (upstream day-use /
            // non-camping facilities, un-xref'd parks) surface as empty rather
            // than through a live render-only fetch.
            val (start, end) = displayWindow(poiId, startDate, endDate, providerRefs, dateResolver)
            return emptyPoiAvailability(poiId, start, end)
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

/**
 * SUNSET — render-only fallback for POIs with no obtainable catalog.
 *
 * Serves POIs that have a `provider_ref` but no linked `reservables`. With
 * ReserveAmerica now cataloged (see `ReserveAmericaSitesEtl`), the remaining
 * population is upstream bad data, NOT missing ingestion:
 *   - Aspira POIs with `provider_ref.resourceLocationId == null` (Parks Canada
 *     join-by-name entries that never got a join key);
 *   - RecGov non-campsite facilities (day-use areas, cabins, lookouts, group
 *     sites, boat ramps, visitor centers) with no standard campsite roster;
 *   - ReserveCalifornia open-camping / SVRA grids the sites ETL intentionally skips.
 *
 * Any vendor with an obtainable catalog MUST be cataloged (SitesEtl + joiner)
 * rather than rely on this path. It is earmarked for removal once the
 * catalogless population reaches zero. Do not add new vendors here.
 */
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
            bookingHorizon = CapabilityLimit(EMPTY_WINDOW_HORIZON_DAYS, ChronoUnit.DAYS),
            maxDays = EMPTY_WINDOW_MAX_DAYS,
            defaultDays = EMPTY_WINDOW_DEFAULT_DAYS,
        )
    return window.startDate to window.endDate
}
