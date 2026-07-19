package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.support.ReserveAmericaException
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Widest single-tick poll window for ReserveAmerica / Active Network. Latent
 * until watches turn on for this vendor (`supportsInternalPolling` is still false)
 * pending cadence/load validation; declared for capability completeness.
 */
private const val RESERVEAMERICA_MAX_POLL_WINDOW_DAYS = 30

class ReserveAmericaAvailabilityProvider(
    internal val tenant: ReserveAmericaTenant,
    private val availabilityClient: ReserveAmericaAvailabilityClient,
    private val enabled: Boolean,
) : AvailabilityProvider {
    override val id: AvailabilityProviderId = AvailabilityProviderId.RESERVEAMERICA

    override val capabilities: AvailabilityProviderCapabilities =
        AvailabilityProviderCapabilities(
            // The live endpoint can support polling, but watches stay off until
            // cadence and upstream load limits are validated for Active Network.
            supportsInternalPolling = false,
            bookingHorizonDays = tenant.bookingHorizonDays,
            maxPollWindowDays = RESERVEAMERICA_MAX_POLL_WINDOW_DAYS,
        )

    override fun isEnabled(): Boolean = enabled

    override suspend fun availability(
        ref: BookingProviderRef,
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
                    CampsiteDayObservation(
                        campsiteId = null,
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
        ref: BookingProviderRef,
        campsites: List<CatalogCampsiteRef>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val reserveAmericaRef = reserveAmericaRefOrThrow(ref)
        val contractCode = contractCode(reserveAmericaRef)
        val data = fetch(contractCode, reserveAmericaRef.parkId, startDate, endDate)
        val observations =
            campsites.flatMap { reservable ->
                observationsForReservable(
                    campsite = reservable,
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

    private suspend fun fetch(
        contractCode: String,
        parkId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): ReserveAmericaAvailability =
        runWithErrorMapping {
            availabilityClient.fetch(
                host = tenant.host,
                contractCode = contractCode,
                parkId = parkId,
                startDate = startDate,
                endDate = endDate,
            )
        }

    private fun observationsForReservable(
        campsite: CatalogCampsiteRef,
        byDate: Map<LocalDate, AvailabilityStatus>,
        dates: List<LocalDate>,
        observedAt: Instant,
    ): List<CampsiteDayObservation> =
        observationsForReservable(
            campsiteId = campsite.campsiteId,
            byDate = byDate,
            dates = dates,
            observedAt = observedAt,
        )

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
        contractCode: String,
        parkId: String,
        startDate: LocalDate,
        endDate: LocalDate,
        observations: List<CampsiteDayObservation>,
        campsiteId: Long? = null,
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
            campsiteId = campsiteId,
        )

    private fun reserveAmericaRefOrThrow(ref: BookingProviderRef): BookingProviderRef.ReserveAmerica =
        (ref as? BookingProviderRef.ReserveAmerica)
            ?: throw AvailabilityProviderError.WrongRefType(id.name.lowercase(), ref::class.simpleName ?: "unknown")

    private fun contractCode(ref: BookingProviderRef.ReserveAmerica): String {
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
