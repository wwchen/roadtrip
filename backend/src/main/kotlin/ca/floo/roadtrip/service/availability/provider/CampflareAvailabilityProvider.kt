package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.campflare.CampflareAvailability
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.support.CampflareException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class CampflareAvailabilityProvider(
    private val availabilityClient: CampflareAvailabilityClient,
    private val enabled: Boolean,
) : AvailabilityProvider {
    override val id: BookingProvider = BookingProvider.CAMPFLARE

    override val capabilities: AvailabilityProviderCapabilities =
        AvailabilityProviderCapabilities(
            supportsInternalPolling = true,
            bookingHorizonDays = CAMPFLARE_BOOKING_HORIZON_DAYS,
            maxPollWindowDays = CAMPFLARE_MAX_POLL_WINDOW_DAYS,
        )

    override fun isEnabled(): Boolean = enabled

    override fun supportsCampground(campground: Campground): Boolean =
        isEnabled() && campground.dataProviderRef is DataProviderRef.Campflare

    override fun parentRefFor(campground: Campground): BookingProviderRef? {
        val cfRef = campground.dataProviderRef as? DataProviderRef.Campflare ?: return null
        return BookingProviderRef.Campflare(campgroundId = cfRef.id)
    }

    override fun reservationUrlTemplate(
        campsite: Campsite,
        parentRef: BookingProviderRef,
    ): String? = RecGovBookingUrl.templateFromUrl(campsite.reservationUrl)

    override suspend fun availability(
        campground: Campground,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val campgroundId = campflareIdOrThrow(campground)
        val data = fetch(campgroundId, startDate, endDate)
        val cg = data.campgrounds[campgroundId]
        val observations =
            cg
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
            campgroundId = campgroundId,
            startDate = startDate,
            endDate = endDate,
            observations = observations,
        )
    }

    override suspend fun catalogAvailability(
        campground: Campground,
        campsites: List<Campsite>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        if (campsites.isEmpty()) {
            return availability(campground, startDate, endDate)
        }
        val campgroundId = campflareIdOrThrow(campground)
        val data = fetch(campgroundId, startDate, endDate)
        val byCampsiteId =
            data
                .campgrounds[campgroundId]
                ?.campsiteAvailability
                .orEmpty()
                .associateBy { it.campsiteId }
        val days = dates(startDate, endDate)
        val observations =
            campsites.flatMap { campsite ->
                observationsForReservable(
                    campsiteId = campsite.id,
                    byDate = byCampsiteId[campsite.campflareVendorId()]?.availability.orEmpty(),
                    dates = days,
                    data = data,
                )
            }
        return batch(
            campgroundId = campgroundId,
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
            availabilityClient.fetchAvailability(
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

    private fun campflareIdOrThrow(campground: Campground): String =
        (campground.dataProviderRef as? DataProviderRef.Campflare)?.id
            ?: throw AvailabilityProviderError.WrongRefType(id.name.lowercase(), campground.dataProviderRef::class.simpleName ?: "unknown")

    private suspend inline fun <T> runWithErrorMapping(crossinline block: suspend () -> T): T =
        mapUpstreamErrors(
            vendorError = { e: CampflareException ->
                upstreamAvailabilityError(cause = e, httpStatus = e.httpStatus, blockedStatuses = campflareBlockedStatuses)
            },
        ) { block() }

    companion object {
        /** An auth failure is a key problem, not an outage: retrying it is
         *  pointless, so it is classified as blocked rather than 5xx. */
        private val campflareBlockedStatuses = setOf(HTTP_UNAUTHORIZED, HTTP_FORBIDDEN)
        private const val CAMPFLARE_PROVIDER = "campflare"
        private const val CAMPFLARE_BOOKING_HORIZON_DAYS = 365
        private const val CAMPFLARE_MAX_POLL_WINDOW_DAYS = 60
    }
}

private fun Campsite.campflareVendorId(): String =
    bookingProviderRef
        ?.takeIf { bookingProvider == BookingProvider.CAMPFLARE.id }
        ?: dataProviderRef.serialize()

private fun dates(
    startDate: LocalDate,
    endDate: LocalDate,
): List<LocalDate> =
    (0 until ChronoUnit.DAYS.between(startDate, endDate).toInt())
        .map { startDate.plusDays(it.toLong()) }
