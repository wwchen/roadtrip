package ca.floo.roadtrip.service.booking.adapters.aspira

import ca.floo.roadtrip.client.AspiraException
import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.repo.CachedAspiraAvailability
import ca.floo.roadtrip.service.api.AspiraCatalogReservable
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import ca.floo.roadtrip.service.api.availableDatesAspira
import ca.floo.roadtrip.service.api.fetchAndClassifyAspira
import ca.floo.roadtrip.service.api.fetchAndClassifyAspiraCatalog
import ca.floo.roadtrip.service.api.fetchAndClassifyAspiraResource
import ca.floo.roadtrip.service.booking.AvailabilityRequest
import ca.floo.roadtrip.service.booking.AvailableDatesRequest
import ca.floo.roadtrip.service.booking.BookingCapabilities
import ca.floo.roadtrip.service.booking.BookingProvider
import ca.floo.roadtrip.service.booking.BookingProviderError
import ca.floo.roadtrip.service.booking.BookingProviderId
import ca.floo.roadtrip.service.booking.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.booking.ReservableAvailabilityRequest
import java.time.LocalDate

/**
 * Aspira NextGen adapter. One adapter *class* for the whole vendor; one
 * adapter *instance* per upstream host (Parks Canada, BC Parks, WA State
 * Parks). Tenant-shaped data — host, vendor code, booking horizon —
 * lives in [AspiraTenant], not in code branches.
 *
 * The downstream classifier (`fetchAndClassifyAspira`) takes `mapId: Int`;
 * the column type is `Long`. We narrow at the boundary and reject
 * out-of-range values to surface the truncation rather than silently
 * dropping the high bits.
 */
class AspiraBookingProvider(
    private val tenant: AspiraTenant,
    private val cache: CachedAspiraAvailability,
) : BookingProvider {
    override val id: BookingProviderId = BookingProviderId.ASPIRA

    override val capabilities: BookingCapabilities =
        BookingCapabilities(
            supportsAvailability = true,
            // Alert poller is rec.gov-only today; Aspira polling is planned
            // (see RFC 0007). Keep this honest until the poller adapter lands.
            supportsAlerts = false,
            supportsAutoBook = false,
            bookingHorizonDays = tenant.bookingHorizonDays,
        )

    override suspend fun availability(req: AvailabilityRequest): AvailabilityResponseDto {
        val mapId = mapIdOrThrow(req.ref)
        val days = daysBetween(req.startDate, req.endDate)
        return runWithErrorMapping {
            fetchAndClassifyAspira(
                cache = cache,
                host = tenant.host,
                mapId = mapId,
                today = req.startDate,
                days = days,
                force = req.force,
                minNights = 1,
                reservableVendor = tenant.vendorCode,
            )
        }
    }

    override suspend fun availableDates(req: AvailableDatesRequest): List<String> {
        val mapId = mapIdOrThrow(req.ref)
        val days = daysBetween(req.startDate, req.endDate)
        return runWithErrorMapping {
            availableDatesAspira(cache, tenant.host, mapId, req.startDate, days)
        }
    }

    override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityResponseDto {
        val ref = aspiraRefOrThrow(req.ref)
        val parentMapId = mapIdOrThrow(ref.mapId)
        val targets =
            req.reservables.map {
                AspiraCatalogReservable(
                    rid = it.rid,
                    resourceId = it.vendorId,
                    mapId = it.mapId?.let(::mapIdOrThrow),
                    resourceLocationId = it.resourceLocationId?.let { value -> intOrThrow("resourceLocationId", value) },
                )
            }
        return runWithErrorMapping {
            fetchAndClassifyAspiraCatalog(
                cache = cache,
                host = tenant.host,
                parentMapId = parentMapId,
                reservables = targets,
                today = req.startDate,
                days = daysBetween(req.startDate, req.endDate),
                force = req.force,
                minNights = 1,
            )
        }
    }

    override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityResponseDto {
        val mapId = mapIdOrThrow(req.ref)
        val days = daysBetween(req.startDate, req.endDate)
        return runWithErrorMapping {
            fetchAndClassifyAspiraResource(
                cache = cache,
                host = tenant.host,
                mapId = mapId,
                resourceId = req.vendorId,
                reservableVendor = tenant.vendorCode,
                today = req.startDate,
                days = days,
                force = req.force,
                minNights = 1,
            )
        }
    }

    private fun daysBetween(
        startDate: LocalDate,
        endDate: LocalDate,
    ): Int =
        java.time.temporal.ChronoUnit.DAYS
            .between(startDate, endDate)
            .toInt()

    /**
     * Pull the map id and narrow Long → Int. Real Aspira ids fit comfortably
     * in 32 bits; rejecting an out-of-range value loudly is better than
     * silent truncation.
     */
    private fun mapIdOrThrow(ref: ProviderRef): Int {
        val ar = aspiraRefOrThrow(ref)
        return intOrThrow("mapId", ar.mapId)
    }

    private fun mapIdOrThrow(mapId: Long): Int = intOrThrow("mapId", mapId)

    private fun aspiraRefOrThrow(ref: ProviderRef): ProviderRef.Aspira =
        (ref as? ProviderRef.Aspira)
            ?: throw BookingProviderError.WrongRefType(id, ref::class.simpleName ?: "unknown")

    private fun intOrThrow(
        label: String,
        value: Long,
    ): Int {
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw BookingProviderError.UpstreamUnavailable(
                IllegalStateException("aspira $label $value does not fit in Int"),
            )
        }
        return value.toInt()
    }

    private inline fun <T> runWithErrorMapping(block: () -> T): T =
        try {
            block()
        } catch (e: BookingProviderError) {
            throw e
        } catch (e: AspiraException) {
            when {
                e.httpStatus == 429 -> throw BookingProviderError.RateLimited(e)
                e.httpStatus == 503 || e.message?.contains("WAF") == true ->
                    throw BookingProviderError.UpstreamBlocked(e)
                else -> throw BookingProviderError.UpstreamUnavailable(e)
            }
        } catch (e: Exception) {
            throw BookingProviderError.UpstreamUnavailable(e)
        }
}
