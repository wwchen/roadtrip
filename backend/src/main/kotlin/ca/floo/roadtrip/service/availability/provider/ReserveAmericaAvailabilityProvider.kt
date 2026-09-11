package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.BookingTenant
import ca.floo.roadtrip.support.ReserveAmericaException
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val RESERVEAMERICA_BOOKING_HORIZON_DAYS = 270
private const val RESERVEAMERICA_MAX_POLL_WINDOW_DAYS = 30

class ReserveAmericaAvailabilityProvider(
    tenants: List<BookingTenant>,
    private val availabilityClient: ReserveAmericaAvailabilityClient,
    private val enabled: Boolean,
) : AvailabilityProvider {
    private val tenantsByCode: Map<String, BookingTenant> =
        tenants.mapNotNull { tenant -> tenant.code?.let { it to tenant } }.toMap()

    override val id: BookingProvider = BookingProvider.RESERVEAMERICA

    override val capabilities: AvailabilityProviderCapabilities =
        AvailabilityProviderCapabilities(
            supportsInternalPolling = false,
            bookingHorizonDays = RESERVEAMERICA_BOOKING_HORIZON_DAYS,
            maxPollWindowDays = RESERVEAMERICA_MAX_POLL_WINDOW_DAYS,
        )

    override fun isEnabled(): Boolean = enabled

    override fun supportsCampground(campground: Campground): Boolean {
        val ref = claimedRef(campground) ?: return false
        return isEnabled() && ref is BookingProviderRef.ReserveAmerica && ref.contractCode in tenantsByCode
    }

    override suspend fun availability(
        campground: Campground,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val reserveAmericaRef = reserveAmericaRefOrThrow(campground)
        val tenant = tenantForRef(reserveAmericaRef)
        val data = fetch(tenant, reserveAmericaRef.parkId, startDate, endDate)
        val observations =
            data.statuses.flatMap { (_, byDate) ->
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
            scope =
                BookingProviderRef.ReserveAmerica(
                    contractCode = tenant.contractCode,
                    parkId = reserveAmericaRef.parkId,
                ),
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
        val reserveAmericaRef = reserveAmericaRefOrThrow(campground)
        val tenant = tenantForRef(reserveAmericaRef)
        val data = fetch(tenant, reserveAmericaRef.parkId, startDate, endDate)
        val observations =
            campsites.flatMap { campsite ->
                observationsForReservable(
                    campsiteId = campsite.id,
                    byDate = data.statuses[campsite.reserveAmericaVendorId()].orEmpty(),
                    dates = dates(startDate, endDate),
                    observedAt = data.observedAt,
                )
            }
        return batch(
            scope =
                BookingProviderRef.ReserveAmerica(
                    contractCode = tenant.contractCode,
                    parkId = reserveAmericaRef.parkId,
                ),
            startDate = startDate,
            endDate = endDate,
            observations = observations,
        )
    }

    private fun tenantForRef(ref: BookingProviderRef.ReserveAmerica): BookingTenant =
        ref.contractCode?.let { tenantsByCode[it] }
            ?: throw AvailabilityProviderError.Misconfigured(
                providerId = id.name.lowercase(),
                reason = "contract '${ref.contractCode}' is not configured",
                cause = IllegalArgumentException("reserveamerica contract '${ref.contractCode}' is not configured"),
            )

    private suspend fun fetch(
        tenant: BookingTenant,
        parkId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): ReserveAmericaAvailability =
        runWithErrorMapping {
            availabilityClient.fetch(
                host = tenant.host,
                contractCode = tenant.contractCode,
                parkId = parkId,
                startDate = startDate,
                endDate = endDate,
            )
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
        scope: BookingProviderRef.ReserveAmerica,
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
            scope = scope,
            campsiteId = campsiteId,
        )

    private fun reserveAmericaRefOrThrow(campground: Campground): BookingProviderRef.ReserveAmerica =
        (claimedRef(campground) as? BookingProviderRef.ReserveAmerica)
            ?: throw AvailabilityProviderError.WrongRefType(id.name.lowercase(), campground.bookingProvider ?: "null")

    private suspend inline fun <T> runWithErrorMapping(crossinline block: suspend () -> T): T =
        mapUpstreamErrors(
            // No blocked statuses: ReserveAmerica has no bot challenge we can
            // tell apart from an outage, so everything but 429 is retryable-5xx.
            vendorError = { e: ReserveAmericaException -> upstreamAvailabilityError(cause = e, httpStatus = e.httpStatus) },
        ) { block() }
}

/** Registry rows for ReserveAmerica always carry a contract code — the map is keyed by it. */
private val BookingTenant.contractCode: String
    get() = requireNotNull(code) { "reserveamerica tenant '$host' has no contract code" }

private fun Campsite.reserveAmericaVendorId(): String =
    bookingProviderRef
        ?.takeIf { bookingProvider == BookingProvider.RESERVEAMERICA.id }
        ?: dataProviderRef.serialize()

private fun dates(
    startDate: LocalDate,
    endDate: LocalDate,
): List<LocalDate> =
    (0 until ChronoUnit.DAYS.between(startDate, endDate).toInt())
        .map { startDate.plusDays(it.toLong()) }
