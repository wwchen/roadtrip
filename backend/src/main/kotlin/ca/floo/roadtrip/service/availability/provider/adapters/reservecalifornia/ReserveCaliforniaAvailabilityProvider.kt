package ca.floo.roadtrip.service.availability.provider.adapters.reservecalifornia

import ca.floo.roadtrip.clients.reservecalifornia.ReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.clients.reservecalifornia.ReserveCaliforniaException
import ca.floo.roadtrip.clients.reservecalifornia.ReserveCaliforniaGridAvailability
import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.CampsiteDayObservation
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityClient
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderCapabilities
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderError
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.availability.provider.CatalogCampsiteRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class ReserveCaliforniaAvailabilityProvider(
    private val client: ReserveCaliforniaAvailabilityClient,
    private val clock: Clock = Clock.systemUTC(),
) : AvailabilityProvider,
    AvailabilityClient {
    override val id: AvailabilityProviderId = AvailabilityProviderId.RESERVECALIFORNIA

    override val capabilities: AvailabilityProviderCapabilities =
        AvailabilityProviderCapabilities(
            supportsAvailability = true,
            supportsAlerts = false,
            bookingHorizonDays = BOOKING_HORIZON_DAYS,
            maxPollWindowDays = MAX_POLL_WINDOW_DAYS,
        )

    override suspend fun availability(
        ref: ProviderRef,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val reserveCaliforniaRef = reserveCaliforniaRefOrThrow(ref)
        val grids = fetchFacilities(reserveCaliforniaRef, startDate, endDate)
        val dates = dates(startDate, endDate)
        val observations =
            grids.flatMap { grid ->
                grid.statuses.flatMap { (unitId, byDate) ->
                    observationsForReservable(
                        campsiteId = null,
                        byDate = byDate,
                        dates = dates,
                        observedAt = grid.observedAt,
                    )
                }
            }
        return batch(reserveCaliforniaRef, startDate, endDate, observations)
    }

    override suspend fun catalogAvailability(
        ref: ProviderRef,
        campsites: List<CatalogCampsiteRef>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val reserveCaliforniaRef = reserveCaliforniaRefOrThrow(ref)
        val grids = fetchFacilities(reserveCaliforniaRef, startDate, endDate)
        val byUnit =
            grids
                .flatMap { grid -> grid.statuses.map { (unitId, byDate) -> unitId to (grid.observedAt to byDate) } }
                .toMap()
        val dates = dates(startDate, endDate)
        val observations =
            campsites.flatMap { reservable ->
                val found = byUnit[reservable.vendorId]
                observationsForReservable(
                    campsiteId = reservable.campsiteId,
                    byDate = found?.second.orEmpty(),
                    dates = dates,
                    observedAt = found?.first ?: observedAt(grids),
                )
            }
        return batch(reserveCaliforniaRef, startDate, endDate, observations)
    }

    override suspend fun reservableAvailability(
        ref: ProviderRef,
        vendorId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val reserveCaliforniaRef = reserveCaliforniaRefOrThrow(ref)
        val grids = fetchFacilities(reserveCaliforniaRef, startDate, endDate)
        val found =
            grids
                .flatMap { grid -> grid.statuses.map { (unitId, byDate) -> unitId to (grid.observedAt to byDate) } }
                .toMap()[vendorId]
        return batch(
            reserveCaliforniaRef,
            startDate,
            endDate,
            observationsForReservable(
                campsiteId = null,
                byDate = found?.second.orEmpty(),
                dates = dates(startDate, endDate),
                observedAt = found?.first ?: observedAt(grids),
            ),
        )
    }

    private suspend fun fetchFacilities(
        ref: ProviderRef.ReserveCalifornia,
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<ReserveCaliforniaGridAvailability> =
        runWithErrorMapping {
            coroutineScope {
                ref.facilityIds
                    .map { facilityId ->
                        async {
                            client.fetchGrid(
                                facilityId = facilityId,
                                startDate = startDate,
                                endDate = endDate,
                                minDate = startDate,
                                maxDate = startDate.plusDays(BOOKING_HORIZON_DAYS.toLong()),
                            )
                        }
                    }.awaitAll()
            }
        }

    private fun observationsForReservable(
        campsiteId: Long?,
        byDate: Map<LocalDate, AvailabilityStatus>,
        dates: List<LocalDate>,
        observedAt: Instant,
    ): List<CampsiteDayObservation> =
        dates.map { date ->
            CampsiteDayObservation(
                campsiteId = campsiteId,
                date = date,
                observedAt = observedAt,
                status = byDate[date] ?: AvailabilityStatus.UNKNOWN,
            )
        }

    private fun batch(
        ref: ProviderRef.ReserveCalifornia,
        startDate: LocalDate,
        endDate: LocalDate,
        observations: List<CampsiteDayObservation>,
    ): AvailabilityObservationBatch =
        AvailabilityObservationBatch(
            provider = PROVIDER,
            startDate = startDate,
            endDate = endDate,
            observations = observations,
            cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0L, ttlSeconds = 0L),
            campgroundId = ref.placeId.toString(),
            host = "reservecalifornia.com",
            mapId = ref.facilityIds.joinToString(","),
        )

    private fun reserveCaliforniaRefOrThrow(ref: ProviderRef): ProviderRef.ReserveCalifornia =
        (ref as? ProviderRef.ReserveCalifornia)
            ?: throw AvailabilityProviderError.WrongRefType(id, ref::class.simpleName ?: "unknown")

    private fun observedAt(grids: List<ReserveCaliforniaGridAvailability>): Instant = grids.firstOrNull()?.observedAt ?: Instant.now(clock)

    private suspend fun <T> runWithErrorMapping(block: suspend () -> T): T =
        try {
            block()
        } catch (e: AvailabilityProviderError) {
            throw e
        } catch (e: ReserveCaliforniaException) {
            if (e.httpStatus == 429) throw AvailabilityProviderError.RateLimited(e)
            throw AvailabilityProviderError.UpstreamUnavailable(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AvailabilityProviderError.UpstreamUnavailable(e)
        }

    private companion object {
        const val PROVIDER = "reservecalifornia"
        const val BOOKING_HORIZON_DAYS = 183

        /**
         * Widest single-tick poll window. Latent until watches turn on for
         * ReserveCalifornia (`supportsAlerts` is still false); declared for
         * capability completeness.
         */
        const val MAX_POLL_WINDOW_DAYS = 30
    }
}

private fun dates(
    startDate: LocalDate,
    endDate: LocalDate,
): List<LocalDate> =
    (0 until ChronoUnit.DAYS.between(startDate, endDate).toInt())
        .map { startDate.plusDays(it.toLong()) }
