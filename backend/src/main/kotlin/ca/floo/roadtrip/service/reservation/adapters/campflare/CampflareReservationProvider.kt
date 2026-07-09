package ca.floo.roadtrip.service.reservation.adapters.campflare

import ca.floo.roadtrip.clients.campflare.CampflareAvailability
import ca.floo.roadtrip.clients.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.clients.campflare.CampflareException
import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.ReservableDayObservation
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.reservation.AvailabilityClient
import ca.floo.roadtrip.service.reservation.CatalogReservableRef
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class CampflareReservationProvider(
    private val client: CampflareAvailabilityClient,
) : ReservationProvider,
    AvailabilityClient {
    override val id: ReservationProviderId = ReservationProviderId.CAMPFLARE

    override val capabilities: ReservationProviderCapabilities =
        ReservationProviderCapabilities(
            supportsAvailability = true,
            supportsAlerts = false,
            bookingHorizonDays = CAMPFLARE_BOOKING_HORIZON_DAYS,
            maxPollWindowDays = CAMPFLARE_MAX_POLL_WINDOW_DAYS,
        )

    override suspend fun availability(
        ref: ProviderRef,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val campflareRef = campflareRefOrThrow(ref)
        val data = fetch(campflareRef.campgroundId, startDate, endDate)
        val campground = data.campgrounds[campflareRef.campgroundId]
        val observations =
            campground
                ?.campsiteAvailability
                .orEmpty()
                .flatMap { campsite ->
                    observationsForReservable(
                        rid = rid(campsite.campsiteId),
                        byDate = campsite.availability,
                        dates = dates(startDate, endDate),
                        data = data,
                    )
                }
        return batch(
            campgroundId = campflareRef.campgroundId,
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
        if (reservables.isEmpty()) {
            return availability(ref, startDate, endDate)
        }
        val campflareRef = campflareRefOrThrow(ref)
        val data = fetch(campflareRef.campgroundId, startDate, endDate)
        val byCampsiteId =
            data
                .campgrounds[campflareRef.campgroundId]
                ?.campsiteAvailability
                .orEmpty()
                .associateBy { it.campsiteId }
        val days = dates(startDate, endDate)
        val observations =
            reservables.flatMap { reservable ->
                observationsForReservable(
                    rid = reservable.rid,
                    byDate = byCampsiteId[reservable.vendorId]?.availability.orEmpty(),
                    dates = days,
                    data = data,
                )
            }
        return batch(
            campgroundId = campflareRef.campgroundId,
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
        val target = CatalogReservableRef(rid = rid(vendorId), vendorId = vendorId)
        return catalogAvailability(
            ref = ref,
            reservables = listOf(target),
            startDate = startDate,
            endDate = endDate,
        ).copy(reservableId = target.rid)
    }

    private suspend fun fetch(
        campgroundId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): CampflareAvailability =
        runWithErrorMapping {
            client.fetchAvailability(
                campgroundIds = listOf(campgroundId),
                startDate = startDate,
                endDate = endDate,
            )
        }

    private fun observationsForReservable(
        rid: String,
        byDate: Map<LocalDate, AvailabilityStatus>,
        dates: List<LocalDate>,
        data: CampflareAvailability,
    ): List<ReservableDayObservation> =
        dates.map { date ->
            ReservableDayObservation(
                reservableId = rid,
                date = date,
                observedAt = data.observedAt,
                status = byDate[date] ?: AvailabilityStatus.UNKNOWN,
            )
        }

    private fun batch(
        campgroundId: String,
        startDate: LocalDate,
        endDate: LocalDate,
        observations: List<ReservableDayObservation>,
    ): AvailabilityObservationBatch =
        AvailabilityObservationBatch(
            provider = CAMPFLARE_PROVIDER,
            startDate = startDate,
            endDate = endDate,
            observations = observations,
            cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0L, ttlSeconds = 0L),
            campgroundId = campgroundId,
        )

    private fun campflareRefOrThrow(ref: ProviderRef): ProviderRef.Campflare =
        (ref as? ProviderRef.Campflare)
            ?: throw ReservationProviderError.WrongRefType(id, ref::class.simpleName ?: "unknown")

    private suspend inline fun <T> runWithErrorMapping(crossinline block: suspend () -> T): T =
        try {
            block()
        } catch (e: ReservationProviderError) {
            throw e
        } catch (e: CampflareException) {
            when {
                e.httpStatus == 429 -> throw ReservationProviderError.RateLimited(e)
                e.httpStatus == 401 || e.httpStatus == 403 -> throw ReservationProviderError.UpstreamBlocked(e)
                e.httpStatus != null && e.httpStatus in 500..599 -> throw ReservationProviderError.UpstreamUnavailable(e)
                else -> throw ReservationProviderError.UpstreamUnavailable(e)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ReservationProviderError.UpstreamUnavailable(e)
        }

    companion object {
        private const val CAMPFLARE_PROVIDER = "campflare"
        private const val CAMPFLARE_BOOKING_HORIZON_DAYS = 365
        private const val CAMPFLARE_MAX_POLL_WINDOW_DAYS = 60
    }
}

private fun dates(
    startDate: LocalDate,
    endDate: LocalDate,
): List<LocalDate> =
    (0 until ChronoUnit.DAYS.between(startDate, endDate).toInt())
        .map { startDate.plusDays(it.toLong()) }

private fun rid(campsiteId: String): String = "site:campflare:$campsiteId"
