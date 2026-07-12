package ca.floo.roadtrip.service.availability.provider.adapters.campflare

import ca.floo.roadtrip.clients.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.exceptions.CampflareException
import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.CampsiteDayObservation
import ca.floo.roadtrip.models.availability.campflare.CampflareAvailability
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderError
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.availability.provider.CatalogCampsiteRef
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class CampflareAvailabilityProvider(
    private val client: CampflareAvailabilityClient,
    private val enabled: Boolean,
) : AvailabilityProvider {
    override val id: AvailabilityProviderId = AvailabilityProviderId.CAMPFLARE

    override val capabilities: AvailabilityProviderCapabilities =
        AvailabilityProviderCapabilities(
            supportsAvailability = true,
            pollableForAlerts = false,
            bookingHorizonDays = CAMPFLARE_BOOKING_HORIZON_DAYS,
            maxPollWindowDays = CAMPFLARE_MAX_POLL_WINDOW_DAYS,
        )

    override fun isEnabled(): Boolean = enabled

    override fun canHandle(ref: ProviderRef): Boolean = isEnabled() && ref is ProviderRef.Campflare

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
                        campsiteId = null,
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
        campsites: List<CatalogCampsiteRef>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        if (campsites.isEmpty()) {
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
            campsites.flatMap { reservable ->
                observationsForReservable(
                    campsiteId = reservable.campsiteId,
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
        campsiteId: Long?,
        byDate: Map<LocalDate, AvailabilityStatus>,
        dates: List<LocalDate>,
        data: CampflareAvailability,
    ): List<CampsiteDayObservation> =
        dates.map { date ->
            CampsiteDayObservation(
                campsiteId = campsiteId,
                date = date,
                observedAt = data.observedAt,
                status = byDate[date] ?: AvailabilityStatus.UNKNOWN,
            )
        }

    private fun batch(
        campgroundId: String,
        startDate: LocalDate,
        endDate: LocalDate,
        observations: List<CampsiteDayObservation>,
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
            ?: throw AvailabilityProviderError.WrongRefType(id, ref::class.simpleName ?: "unknown")

    private suspend inline fun <T> runWithErrorMapping(crossinline block: suspend () -> T): T =
        try {
            block()
        } catch (e: AvailabilityProviderError) {
            throw e
        } catch (e: CampflareException) {
            when {
                e.httpStatus == 429 -> throw AvailabilityProviderError.RateLimited(e)
                e.httpStatus == 401 || e.httpStatus == 403 -> throw AvailabilityProviderError.UpstreamBlocked(e)
                e.httpStatus != null && e.httpStatus in 500..599 -> throw AvailabilityProviderError.UpstreamUnavailable(e)
                else -> throw AvailabilityProviderError.UpstreamUnavailable(e)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AvailabilityProviderError.UpstreamUnavailable(e)
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
