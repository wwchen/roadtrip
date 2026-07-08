package ca.floo.roadtrip.service.reservation.adapters.reserveamerica

import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaException
import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.ReservableDayObservation
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.reservation.AvailabilityClient
import ca.floo.roadtrip.service.reservation.CapabilityLimit
import ca.floo.roadtrip.service.reservation.CatalogReservableRef
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
    val bookingHorizon: CapabilityLimit,
)

/**
 * Widest single-tick poll window for ReserveAmerica / Active Network. Latent
 * until watches turn on for this vendor (`supportsAlerts` is still false)
 * pending cadence/load validation; declared for capability completeness.
 */
private const val RESERVEAMERICA_MAX_POLL_WINDOW_DAYS = 30
private const val RESERVEAMERICA_FETCH_WINDOW_DAYS = 14

class ReserveAmericaReservationProvider(
    private val tenant: ReserveAmericaTenant,
    private val client: ReserveAmericaAvailabilityClient,
) : ReservationProvider,
    AvailabilityClient {
    override val id: ReservationProviderId = ReservationProviderId.RESERVEAMERICA

    override val capabilities: ReservationProviderCapabilities =
        ReservationProviderCapabilities(
            supportsAvailability = true,
            // The live endpoint can support polling, but watches stay off until
            // cadence and upstream load limits are validated for Active Network.
            supportsAlerts = false,
            maxPollWindowDays = RESERVEAMERICA_MAX_POLL_WINDOW_DAYS,
            bookingHorizon = tenant.bookingHorizon,
            fetchWindowCap = CapabilityLimit(RESERVEAMERICA_FETCH_WINDOW_DAYS, ChronoUnit.DAYS),
        )

    override suspend fun availability(
        ref: ProviderRef,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val reserveAmericaRef = reserveAmericaRefOrThrow(ref)
        val contractCode = contractCode(reserveAmericaRef)
        val data = fetch(contractCode, reserveAmericaRef.parkId, startDate, endDate)
        val observations =
            data.statuses.flatMap { (siteId, byDate) ->
                // Dense fill is intentional for the campground-level matrix:
                // catalog-less callers still need every visible site/date cell.
                dates(startDate, endDate).map { date ->
                    ReservableDayObservation(
                        reservableId = providerScopedReservableId(contractCode, siteId),
                        date = date,
                        observedAt = data.observedAt,
                        status = byDate[date] ?: AvailabilityStatus.UNKNOWN,
                    )
                }
            }
        return batch(
            contractCode = contractCode,
            parkId = reserveAmericaRef.parkId,
            startDate = startDate,
            endDate = endDate,
            observations = observations,
        )
    }

    override suspend fun catalogAvailability(
        ref: ProviderRef,
        reservables: List<CatalogReservableRef>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val reserveAmericaRef = reserveAmericaRefOrThrow(ref)
        val contractCode = contractCode(reserveAmericaRef)
        val data = fetch(contractCode, reserveAmericaRef.parkId, startDate, endDate)
        val observations =
            reservables.flatMap { reservable ->
                observationsForReservable(
                    reservableId = reservable.catalogId.toString(),
                    byDate = data.statuses[reservable.vendorId].orEmpty(),
                    dates = dates(startDate, endDate),
                    observedAt = data.observedAt,
                )
            }
        return batch(
            contractCode = contractCode,
            parkId = reserveAmericaRef.parkId,
            startDate = startDate,
            endDate = endDate,
            observations = observations,
        )
    }

    override suspend fun reservableAvailability(
        ref: ProviderRef,
        vendorId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val reserveAmericaRef = reserveAmericaRefOrThrow(ref)
        val contractCode = contractCode(reserveAmericaRef)
        val data = fetch(contractCode, reserveAmericaRef.parkId, startDate, endDate)
        val observations =
            observationsForReservable(
                reservableId = providerScopedReservableId(contractCode, vendorId),
                byDate = data.statuses[vendorId].orEmpty(),
                dates = dates(startDate, endDate),
                observedAt = data.observedAt,
            )
        return batch(
            contractCode = contractCode,
            parkId = reserveAmericaRef.parkId,
            startDate = startDate,
            endDate = endDate,
            observations = observations,
            reservableId = providerScopedReservableId(contractCode, vendorId),
        )
    }

    override fun availabilityFetchCost(
        startDate: LocalDate,
        endDate: LocalDate,
    ): Long {
        if (!endDate.isAfter(startDate)) return 0L
        val days = ChronoUnit.DAYS.between(startDate, endDate)
        return (days + RESERVEAMERICA_FETCH_WINDOW_DAYS - 1) / RESERVEAMERICA_FETCH_WINDOW_DAYS
    }

    private suspend fun fetch(
        contractCode: String,
        parkId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): ReserveAmericaAvailability =
        runWithErrorMapping {
            client.fetch(
                host = tenant.host,
                contractCode = contractCode,
                parkId = parkId,
                startDate = startDate,
                endDate = endDate,
            )
        }

    private fun observationsForReservable(
        reservableId: String,
        byDate: Map<LocalDate, AvailabilityStatus>,
        dates: List<LocalDate>,
        observedAt: Instant,
    ): List<ReservableDayObservation> =
        dates.map { date ->
            ReservableDayObservation(
                reservableId = reservableId,
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

private fun providerScopedReservableId(
    contractCode: String,
    siteId: String,
): String = "site:reserveamerica_${contractCode.lowercase()}:$siteId"
