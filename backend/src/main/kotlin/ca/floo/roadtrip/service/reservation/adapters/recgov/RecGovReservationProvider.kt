package ca.floo.roadtrip.service.reservation.adapters.recgov

import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import ca.floo.roadtrip.service.api.recgov.CachedAvailability
import ca.floo.roadtrip.service.api.recgov.availableDatesRecgov
import ca.floo.roadtrip.service.api.recgov.fetchAndClassifyRecgov
import ca.floo.roadtrip.service.api.recgov.fetchAndClassifyRecgovCatalog
import ca.floo.roadtrip.service.api.recgov.fetchAndClassifyRecgovReservable
import ca.floo.roadtrip.service.reservation.AvailabilityRequest
import ca.floo.roadtrip.service.reservation.AvailableDatesRequest
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservableAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import ca.floo.roadtrip.service.reservation.ReservationProviderId

/**
 * rec.gov adapter. Wraps the per-month cache + classify pipeline. Vendor-
 * specific error translation lives here; routes only see [ReservationProviderError].
 */
class RecGovReservationProvider(
    private val cache: CachedAvailability,
) : ReservationProvider {
    override val id: ReservationProviderId = ReservationProviderId.RECGOV

    override val capabilities: ReservationProviderCapabilities =
        ReservationProviderCapabilities(
            supportsAvailability = true,
            supportsAlerts = true,
            bookingHorizonDays = RECGOV_BOOKING_HORIZON_DAYS,
        )

    override suspend fun availability(req: AvailabilityRequest): AvailabilityResponseDto {
        val recgovId = recgovIdOrThrow(req.ref)
        return runWithErrorMapping {
            fetchAndClassifyRecgov(
                cache = cache,
                recgovId = recgovId,
                startDate = req.startDate,
                endDate = req.endDate,
                force = req.force,
            )
        }
    }

    override suspend fun availableDates(req: AvailableDatesRequest): List<String> {
        val recgovId = recgovIdOrThrow(req.ref)
        return runWithErrorMapping {
            availableDatesRecgov(cache, recgovId, req.startDate, req.endDate)
        }
    }

    override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityResponseDto {
        if (req.reservables.isEmpty()) {
            return availability(
                AvailabilityRequest(
                    ref = req.ref,
                    startDate = req.startDate,
                    endDate = req.endDate,
                    force = req.force,
                ),
            )
        }
        val recgovId = recgovIdOrThrow(req.ref)
        return runWithErrorMapping {
            fetchAndClassifyRecgovCatalog(
                cache = cache,
                recgovId = recgovId,
                campsiteIds = req.reservables.map { it.vendorId }.toSet(),
                startDate = req.startDate,
                endDate = req.endDate,
                force = req.force,
            )
        }
    }

    override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityResponseDto {
        val recgovId = recgovIdOrThrow(req.ref)
        return runWithErrorMapping {
            fetchAndClassifyRecgovReservable(
                cache = cache,
                recgovId = recgovId,
                campsiteId = req.vendorId,
                startDate = req.startDate,
                endDate = req.endDate,
                force = req.force,
            )
        }
    }

    private fun recgovIdOrThrow(ref: ProviderRef): String =
        when (ref) {
            is ProviderRef.RecGov -> ref.recgovId
            else -> throw ReservationProviderError.WrongRefType(id, ref::class.simpleName ?: "unknown")
        }

    private inline fun <T> runWithErrorMapping(block: () -> T): T =
        try {
            block()
        } catch (e: ReservationProviderError) {
            throw e
        } catch (e: Exception) {
            // The recgov client's exception types aren't a single hierarchy
            // (some throw plain Exception with rate-limit text). Pattern-
            // match on message text the same way the legacy mapper did, but
            // produce typed ReservationProviderError so the route doesn't need
            // to know the upstream's quirks.
            val msg = e.message.orEmpty()
            when {
                msg.contains("429") || msg.contains("rate") -> throw ReservationProviderError.RateLimited(e)
                else -> throw ReservationProviderError.UpstreamUnavailable(e)
            }
        }

    companion object {
        /** rec.gov exposes 6 months of inventory at any time. */
        private const val RECGOV_BOOKING_HORIZON_DAYS: Int = 180
    }
}
