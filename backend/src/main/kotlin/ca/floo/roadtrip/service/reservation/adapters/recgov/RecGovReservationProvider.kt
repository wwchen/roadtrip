package ca.floo.roadtrip.service.reservation.adapters.recgov

import ca.floo.roadtrip.clients.recgov.AvailabilityClient
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.reservation.AvailabilityRequest
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservableAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import ca.floo.roadtrip.service.reservation.ReservationProviderId

/**
 * rec.gov adapter. Vendor-specific error translation lives here; routes only
 * see [ReservationProviderError]. Caching is handled above the adapter by
 * [ca.floo.roadtrip.service.api.SnapshotBackedAvailabilityService] reading
 * the `availability_snapshots` table.
 */
class RecGovReservationProvider(
    private val client: AvailabilityClient,
) : ReservationProvider {
    override val id: ReservationProviderId = ReservationProviderId.RECGOV

    override val capabilities: ReservationProviderCapabilities =
        ReservationProviderCapabilities(
            supportsAvailability = true,
            supportsAlerts = true,
            bookingHorizonDays = RECGOV_BOOKING_HORIZON_DAYS,
        )

    override suspend fun availability(req: AvailabilityRequest): AvailabilityObservationBatch {
        val recgovId = recgovIdOrThrow(req.ref)
        return runWithErrorMapping {
            fetchRecgovAvailabilityObservations(
                client = client,
                recgovId = recgovId,
                startDate = req.startDate,
                endDate = req.endDate,
            )
        }
    }

    override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityObservationBatch {
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
            fetchRecgovCatalogObservations(
                client = client,
                recgovId = recgovId,
                campsiteIds = req.reservables.map { it.vendorId }.toSet(),
                startDate = req.startDate,
                endDate = req.endDate,
            )
        }
    }

    override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityObservationBatch {
        val recgovId = recgovIdOrThrow(req.ref)
        return runWithErrorMapping {
            fetchRecgovReservableObservations(
                client = client,
                recgovId = recgovId,
                campsiteId = req.vendorId,
                startDate = req.startDate,
                endDate = req.endDate,
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
