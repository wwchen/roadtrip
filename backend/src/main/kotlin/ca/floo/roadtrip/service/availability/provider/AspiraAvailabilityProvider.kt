package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.support.AspiraException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val ASPIRA_BOOKING_HORIZON_DAYS = 365
private const val ASPIRA_MAX_POLL_WINDOW_DAYS = 30
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_SERVICE_UNAVAILABLE = 503

/**
 * Aspira NextGen adapter. Single instance covers all tenants (Parks Canada,
 * BC Parks, WA State Parks). Tenant-shaped data — host, vendor code, booking
 * horizon — lives in [AspiraTenant]; the correct one is resolved from the
 * [BookingProviderRef.Aspira.tenant] field at call time.
 */
class AspiraAvailabilityProvider(
    private val tenants: Map<String, AspiraTenant>,
    private val availabilityClient: AspiraAvailabilityClient,
    private val enabled: Boolean,
    private val occupancyEnabled: Boolean = false,
) : AvailabilityProvider {
    override val id: BookingProvider = BookingProvider.ASPIRA

    override val capabilities: AvailabilityProviderCapabilities =
        AvailabilityProviderCapabilities(
            supportsInternalPolling = true,
            bookingHorizonDays = ASPIRA_BOOKING_HORIZON_DAYS,
            maxPollWindowDays = ASPIRA_MAX_POLL_WINDOW_DAYS,
        )

    override fun isEnabled(): Boolean = enabled

    override fun supportsCampground(campground: Campground): Boolean {
        val provider = campground.bookingProvider?.let(BookingProvider::fromIdOrNull) ?: return false
        val ref = campground.bookingProviderRef?.let { BookingProviderRef.parse(provider, it) } ?: return false
        return isEnabled() && ref is BookingProviderRef.Aspira && ref.tenant in tenants
    }

    override suspend fun availability(
        campground: Campground,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val aspiraRef = aspiraRefOrThrow(campground)
        val tenant = tenantForRef(aspiraRef)
        val mapId = mapIdOrThrow(aspiraRef.mapId)
        return runWithErrorMapping {
            fetchAspiraAvailabilityObservations(
                client = availabilityClient,
                host = tenant.host,
                mapId = mapId,
                startDate = startDate,
                endDate = endDate,
                campsiteVendor = tenant.vendorCode,
                mapResourceCodeFamily = tenant.mapResourceCodeFamily,
            )
        }
    }

    override suspend fun catalogAvailability(
        campground: Campground,
        campsites: List<CatalogCampsiteRef>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val aspiraRef = aspiraRefOrThrow(campground)
        val tenant = tenantForRef(aspiraRef)
        val parentMapId = mapIdOrThrow(aspiraRef.mapId)
        val targets =
            campsites.map {
                AspiraCatalogCampsite(
                    campsiteId = it.campsiteId,
                    resourceId = it.vendorId,
                    mapId = it.mapId?.let(::mapIdOrThrow),
                    resourceLocationId = it.resourceLocationId?.let { value -> intOrThrow("resourceLocationId", value) },
                )
            }
        val resourceLocationId =
            aspiraRef.resourceLocationId?.let { intOrThrow("resourceLocationId", it) }
                ?: targets.mapNotNull { it.resourceLocationId }.distinct().singleOrNull()
        return runWithErrorMapping {
            if (occupancyEnabled && resourceLocationId != null) {
                fetchAspiraCatalogOccupancyObservations(
                    client = availabilityClient,
                    host = tenant.host,
                    parentMapId = parentMapId,
                    resourceLocationId = resourceLocationId,
                    campsites = targets,
                    today = startDate,
                    days = ChronoUnit.DAYS.between(startDate, endDate).toInt(),
                )
            } else {
                fetchAspiraCatalogObservations(
                    client = availabilityClient,
                    host = tenant.host,
                    parentMapId = parentMapId,
                    campsites = targets,
                    startDate = startDate,
                    endDate = endDate,
                    mapResourceCodeFamily = tenant.mapResourceCodeFamily,
                )
            }
        }
    }

    override fun reservationUrlTemplate(
        campsite: Campsite,
        parentRef: BookingProviderRef,
        catalogRef: CatalogCampsiteRef,
    ): String? {
        val tenant = (parentRef as? BookingProviderRef.Aspira)?.tenant?.let { tenants[it] } ?: return null
        return AspiraBookingUrl.templateFor(tenant.host, catalogRef.mapId, catalogRef.resourceLocationId, parentRef)
    }

    private fun tenantForRef(ref: BookingProviderRef.Aspira): AspiraTenant =
        tenants[ref.tenant]
            ?: throw AvailabilityProviderError.UpstreamUnavailable(
                IllegalArgumentException("aspira tenant '${ref.tenant}' is not configured"),
            )

    private fun mapIdOrThrow(mapId: Long): Int = intOrThrow("mapId", mapId)

    private fun aspiraRefOrThrow(campground: Campground): BookingProviderRef.Aspira {
        val provider = campground.bookingProvider?.let(BookingProvider::fromIdOrNull)
        val ref = provider?.let { campground.bookingProviderRef?.let { r -> BookingProviderRef.parse(it, r) } }
        return (ref as? BookingProviderRef.Aspira)
            ?: throw AvailabilityProviderError.WrongRefType(id.name.lowercase(), campground.bookingProvider ?: "null")
    }

    private fun intOrThrow(
        label: String,
        value: Long,
    ): Int {
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw AvailabilityProviderError.UpstreamUnavailable(
                IllegalStateException("aspira $label $value does not fit in Int"),
            )
        }
        return value.toInt()
    }

    private inline fun <T> runWithErrorMapping(block: () -> T): T =
        try {
            block()
        } catch (e: AvailabilityProviderError) {
            throw e
        } catch (e: AspiraException) {
            when {
                e.httpStatus == HTTP_TOO_MANY_REQUESTS -> throw AvailabilityProviderError.RateLimited(e)
                e.httpStatus == HTTP_UNAUTHORIZED ||
                    e.httpStatus == HTTP_FORBIDDEN ||
                    e.httpStatus == HTTP_SERVICE_UNAVAILABLE ||
                    e.message?.contains("WAF") == true ->
                    throw AvailabilityProviderError.UpstreamBlocked(e)
                else -> throw AvailabilityProviderError.UpstreamUnavailable(e)
            }
        } catch (e: Exception) {
            throw AvailabilityProviderError.UpstreamUnavailable(e)
        }
}
