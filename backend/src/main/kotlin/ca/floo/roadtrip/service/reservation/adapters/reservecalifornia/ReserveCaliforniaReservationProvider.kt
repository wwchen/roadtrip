package ca.floo.roadtrip.service.reservation.adapters.reservecalifornia

import ca.floo.roadtrip.clients.reservecalifornia.ReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.clients.reservecalifornia.ReserveCaliforniaException
import ca.floo.roadtrip.clients.reservecalifornia.ReserveCaliforniaGridAvailability
import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.ReservableDayObservation
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.reservation.AvailabilityRequest
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.reservation.CatalogReservableRef
import ca.floo.roadtrip.service.reservation.ReservableAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class ReserveCaliforniaReservationProvider(
    private val client: ReserveCaliforniaAvailabilityClient,
    private val clock: Clock = Clock.systemUTC(),
) : ReservationProvider {
    override val id: ReservationProviderId = ReservationProviderId.RESERVECALIFORNIA

    override val capabilities: ReservationProviderCapabilities =
        ReservationProviderCapabilities(
            supportsAvailability = true,
            supportsAlerts = false,
            bookingHorizonDays = BOOKING_HORIZON_DAYS,
        )

    override suspend fun availability(req: AvailabilityRequest): AvailabilityObservationBatch {
        val ref = reserveCaliforniaRefOrThrow(req.ref)
        val grids = fetchFacilities(ref, req.startDate, req.endDate)
        val dates = dates(req.startDate, req.endDate)
        val observations =
            grids.flatMap { grid ->
                grid.statuses.flatMap { (unitId, byDate) ->
                    observationsForReservable(
                        rid = rid(unitId),
                        byDate = byDate,
                        dates = dates,
                        observedAt = grid.observedAt,
                    )
                }
            }
        return batch(ref, req.startDate, req.endDate, observations)
    }

    override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityObservationBatch {
        val ref = reserveCaliforniaRefOrThrow(req.ref)
        val grids = fetchFacilities(ref, req.startDate, req.endDate)
        val byUnit =
            grids
                .flatMap { grid -> grid.statuses.map { (unitId, byDate) -> unitId to (grid.observedAt to byDate) } }
                .toMap()
        val dates = dates(req.startDate, req.endDate)
        val observations =
            req.reservables.flatMap { reservable ->
                val found = byUnit[reservable.vendorId]
                observationsForReservable(
                    rid = reservable.rid,
                    byDate = found?.second.orEmpty(),
                    dates = dates,
                    observedAt = found?.first ?: observedAt(grids),
                )
            }
        return batch(ref, req.startDate, req.endDate, observations)
    }

    override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityObservationBatch {
        val ref = reserveCaliforniaRefOrThrow(req.ref)
        val result =
            catalogAvailability(
                CatalogAvailabilityRequest(
                    ref = ref,
                    reservables = listOf(CatalogReservableRef(rid = rid(req.vendorId), vendorId = req.vendorId)),
                    startDate = req.startDate,
                    endDate = req.endDate,
                    force = req.force,
                ),
            )
        return result.copy(reservableId = rid(req.vendorId))
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
        rid: String,
        byDate: Map<LocalDate, AvailabilityStatus>,
        dates: List<LocalDate>,
        observedAt: Instant,
    ): List<ReservableDayObservation> =
        dates.map { date ->
            ReservableDayObservation(
                reservableId = rid,
                date = date,
                observedAt = observedAt,
                status = byDate[date] ?: AvailabilityStatus.UNKNOWN,
            )
        }

    private fun batch(
        ref: ProviderRef.ReserveCalifornia,
        startDate: LocalDate,
        endDate: LocalDate,
        observations: List<ReservableDayObservation>,
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
            ?: throw ReservationProviderError.WrongRefType(id, ref::class.simpleName ?: "unknown")

    private fun observedAt(grids: List<ReserveCaliforniaGridAvailability>): Instant = grids.firstOrNull()?.observedAt ?: Instant.now(clock)

    private suspend fun <T> runWithErrorMapping(block: suspend () -> T): T =
        try {
            block()
        } catch (e: ReservationProviderError) {
            throw e
        } catch (e: ReserveCaliforniaException) {
            if (e.httpStatus == 429) throw ReservationProviderError.RateLimited(e)
            throw ReservationProviderError.UpstreamUnavailable(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ReservationProviderError.UpstreamUnavailable(e)
        }

    private companion object {
        const val PROVIDER = "reservecalifornia"
        const val BOOKING_HORIZON_DAYS = 183
    }
}

private fun dates(
    startDate: LocalDate,
    endDate: LocalDate,
): List<LocalDate> =
    (0 until ChronoUnit.DAYS.between(startDate, endDate).toInt())
        .map { startDate.plusDays(it.toLong()) }

private fun rid(unitId: String): String = "site:reservecalifornia:$unitId"
