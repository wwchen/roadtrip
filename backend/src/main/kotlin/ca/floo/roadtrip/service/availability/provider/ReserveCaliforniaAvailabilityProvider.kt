package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.reservecalifornia.ReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
import ca.floo.roadtrip.model.availability.reservecalifornia.ReserveCaliforniaGridAvailability
import ca.floo.roadtrip.model.domain.ProviderRef
import ca.floo.roadtrip.support.ReserveCaliforniaException
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
    private val enabled: Boolean,
) : AvailabilityProvider {
    override val id: AvailabilityProviderId = AvailabilityProviderId.RESERVECALIFORNIA

    override val capabilities: AvailabilityProviderCapabilities =
        AvailabilityProviderCapabilities(
            supportsInternalPolling = false,
            bookingHorizonDays = BOOKING_HORIZON_DAYS,
            maxPollWindowDays = MAX_POLL_WINDOW_DAYS,
        )

    override fun isEnabled(): Boolean = enabled

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
            ?: throw AvailabilityProviderError.WrongRefType(id.name.lowercase(), ref::class.simpleName ?: "unknown")

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
         * ReserveCalifornia (`supportsInternalPolling` is still false); declared for
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
