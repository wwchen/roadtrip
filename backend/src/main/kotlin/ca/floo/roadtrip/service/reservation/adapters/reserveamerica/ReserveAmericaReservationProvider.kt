package ca.floo.roadtrip.service.reservation.adapters.reserveamerica

import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaException
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
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class ReserveAmericaTenant(
    val source: String,
    val host: String,
    val contractCode: String,
    val bookingHorizonDays: Int,
)

class ReserveAmericaReservationProvider(
    private val tenant: ReserveAmericaTenant,
    private val client: ReserveAmericaAvailabilityClient,
) : ReservationProvider {
    override val id: ReservationProviderId = ReservationProviderId.RESERVEAMERICA

    override val capabilities: ReservationProviderCapabilities =
        ReservationProviderCapabilities(
            supportsAvailability = true,
            // The live endpoint can support polling, but watches stay off until
            // cadence and upstream load limits are validated for Active Network.
            supportsAlerts = false,
            bookingHorizonDays = tenant.bookingHorizonDays,
        )

    override suspend fun availability(req: AvailabilityRequest): AvailabilityObservationBatch {
        val ref = reserveAmericaRefOrThrow(req.ref)
        val contractCode = contractCode(ref)
        val data = fetch(contractCode, ref.parkId, req.startDate, req.endDate)
        val observations =
            data.statuses.flatMap { (siteId, byDate) ->
                // Dense fill is intentional for the campground-level matrix:
                // catalog-less callers still need every visible site/date cell.
                dates(req.startDate, req.endDate).map { date ->
                    ReservableDayObservation(
                        reservableId = rid(contractCode, siteId),
                        date = date,
                        observedAt = data.observedAt,
                        status = byDate[date] ?: AvailabilityStatus.UNKNOWN,
                    )
                }
            }
        return batch(
            contractCode = contractCode,
            parkId = ref.parkId,
            startDate = req.startDate,
            endDate = req.endDate,
            observations = observations,
        )
    }

    override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityObservationBatch {
        val ref = reserveAmericaRefOrThrow(req.ref)
        val contractCode = contractCode(ref)
        val data = fetch(contractCode, ref.parkId, req.startDate, req.endDate)
        val observations =
            req.reservables.flatMap { reservable ->
                observationsForReservable(
                    reservable = reservable,
                    byDate = data.statuses[reservable.vendorId].orEmpty(),
                    dates = dates(req.startDate, req.endDate),
                    observedAt = data.observedAt,
                )
            }
        return batch(
            contractCode = contractCode,
            parkId = ref.parkId,
            startDate = req.startDate,
            endDate = req.endDate,
            observations = observations,
        )
    }

    override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityObservationBatch {
        val ref = reserveAmericaRefOrThrow(req.ref)
        val contractCode = contractCode(ref)
        val data = fetch(contractCode, ref.parkId, req.startDate, req.endDate)
        val target = CatalogReservableRef(rid = rid(contractCode, req.vendorId), vendorId = req.vendorId)
        val observations =
            observationsForReservable(
                reservable = target,
                byDate = data.statuses[req.vendorId].orEmpty(),
                dates = dates(req.startDate, req.endDate),
                observedAt = data.observedAt,
            )
        return batch(
            contractCode = contractCode,
            parkId = ref.parkId,
            startDate = req.startDate,
            endDate = req.endDate,
            observations = observations,
            reservableId = target.rid,
        )
    }

    private suspend fun fetch(
        contractCode: String,
        parkId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): ReserveAmericaAvailability =
        runWithErrorMapping {
            client.fetch(
                contractCode = contractCode,
                parkId = parkId,
                startDate = startDate,
                endDate = endDate,
            )
        }

    private fun observationsForReservable(
        reservable: CatalogReservableRef,
        byDate: Map<LocalDate, AvailabilityStatus>,
        dates: List<LocalDate>,
        observedAt: Instant,
    ): List<ReservableDayObservation> =
        dates.map { date ->
            ReservableDayObservation(
                reservableId = reservable.rid,
                date = date,
                observedAt = observedAt,
                status = byDate[date] ?: AvailabilityStatus.UNKNOWN,
            )
        }

    private fun batch(
        contractCode: String,
        parkId: String,
        startDate: LocalDate,
        endDate: LocalDate,
        observations: List<ReservableDayObservation>,
        reservableId: String? = null,
    ): AvailabilityObservationBatch =
        AvailabilityObservationBatch(
            provider = "reserveamerica",
            startDate = startDate,
            endDate = endDate,
            observations = observations,
            cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0L, ttlSeconds = 0L),
            campgroundId = parkId,
            host = tenant.host,
            mapId = contractCode,
            reservableId = reservableId,
        )

    private fun reserveAmericaRefOrThrow(ref: ProviderRef): ProviderRef.ReserveAmerica =
        (ref as? ProviderRef.ReserveAmerica)
            ?: throw ReservationProviderError.WrongRefType(id, ref::class.simpleName ?: "unknown")

    private fun contractCode(ref: ProviderRef.ReserveAmerica): String {
        val contract = ref.contractCode ?: tenant.contractCode
        if (!contract.equals(tenant.contractCode, ignoreCase = true)) {
            throw ReservationProviderError.UpstreamUnavailable(
                IllegalArgumentException("reserveamerica contract '$contract' does not match tenant '${tenant.contractCode}'"),
            )
        }
        return tenant.contractCode
    }

    private suspend inline fun <T> runWithErrorMapping(crossinline block: suspend () -> T): T =
        try {
            block()
        } catch (e: ReservationProviderError) {
            throw e
        } catch (e: ReserveAmericaException) {
            when {
                e.httpStatus == 429 -> throw ReservationProviderError.RateLimited(e)
                e.httpStatus != null && e.httpStatus in 500..599 -> throw ReservationProviderError.UpstreamUnavailable(e)
                else -> throw ReservationProviderError.UpstreamUnavailable(e)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ReservationProviderError.UpstreamUnavailable(e)
        }
}

private fun dates(
    startDate: LocalDate,
    endDate: LocalDate,
): List<LocalDate> =
    (0 until ChronoUnit.DAYS.between(startDate, endDate).toInt())
        .map { startDate.plusDays(it.toLong()) }

private fun rid(
    contractCode: String,
    siteId: String,
): String = "site:reserveamerica_${contractCode.lowercase()}:$siteId"
