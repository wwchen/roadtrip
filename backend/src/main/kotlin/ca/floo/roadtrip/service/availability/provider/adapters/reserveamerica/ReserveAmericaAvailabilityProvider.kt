package ca.floo.roadtrip.service.availability.provider.adapters.reserveamerica

import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaException
import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.ReservableDayObservation
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityClient
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderCapabilities
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderError
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.availability.provider.CatalogReservableRef
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

/**
 * Widest single-tick poll window for ReserveAmerica / Active Network. Latent
 * until watches turn on for this vendor (`supportsAlerts` is still false)
 * pending cadence/load validation; declared for capability completeness.
 */
private const val RESERVEAMERICA_MAX_POLL_WINDOW_DAYS = 30

class ReserveAmericaAvailabilityProvider(
    private val tenant: ReserveAmericaTenant,
    private val client: ReserveAmericaAvailabilityClient,
) : AvailabilityProvider,
    AvailabilityClient {
    override val id: AvailabilityProviderId = AvailabilityProviderId.RESERVEAMERICA

    override val capabilities: AvailabilityProviderCapabilities =
        AvailabilityProviderCapabilities(
            supportsAvailability = true,
            // The live endpoint can support polling, but watches stay off until
            // cadence and upstream load limits are validated for Active Network.
            supportsAlerts = false,
            bookingHorizonDays = tenant.bookingHorizonDays,
            maxPollWindowDays = RESERVEAMERICA_MAX_POLL_WINDOW_DAYS,
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
                        reservableId = rid(contractCode, siteId),
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
                    reservable = reservable,
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
        val target = CatalogReservableRef(rid = rid(contractCode, vendorId), vendorId = vendorId)
        val observations =
            observationsForReservable(
                reservable = target,
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
                host = tenant.host,
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
            ?: throw AvailabilityProviderError.WrongRefType(id, ref::class.simpleName ?: "unknown")

    private fun contractCode(ref: ProviderRef.ReserveAmerica): String {
        val contract = ref.contractCode ?: tenant.contractCode
        if (!contract.equals(tenant.contractCode, ignoreCase = true)) {
            throw AvailabilityProviderError.UpstreamUnavailable(
                IllegalArgumentException("reserveamerica contract '$contract' does not match tenant '${tenant.contractCode}'"),
            )
        }
        return tenant.contractCode
    }

    private suspend inline fun <T> runWithErrorMapping(crossinline block: suspend () -> T): T =
        try {
            block()
        } catch (e: AvailabilityProviderError) {
            throw e
        } catch (e: ReserveAmericaException) {
            when {
                e.httpStatus == 429 -> throw AvailabilityProviderError.RateLimited(e)
                e.httpStatus != null && e.httpStatus in 500..599 -> throw AvailabilityProviderError.UpstreamUnavailable(e)
                else -> throw AvailabilityProviderError.UpstreamUnavailable(e)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AvailabilityProviderError.UpstreamUnavailable(e)
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
