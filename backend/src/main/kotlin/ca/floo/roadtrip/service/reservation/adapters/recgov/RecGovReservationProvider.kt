package ca.floo.roadtrip.service.reservation.adapters.recgov

import ca.floo.roadtrip.clients.recgov.AvailabilityClient
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
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
 * [ca.floo.roadtrip.service.api.CachedAvailabilityService] reading current
 * state from the `availability` interval table.
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
            maxPollWindowDays = RECGOV_MAX_POLL_WINDOW_DAYS,
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

    /** rec.gov single-site booking page; the concrete-date [bookingUrl] fills
     *  the window placeholders. [parentRef] is unused — the site id alone
     *  addresses the page. */
    override fun bookingUrlTemplate(
        reservable: Reservable,
        parentRef: ProviderRef,
    ): String = RecGovBookingUrl.template(reservable.rid.vendorId)

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

        /**
         * Widest single-tick poll window. rec.gov shapes availability calls by
         * calendar month, so 60 days keeps a tick to ~2-3 month calls — the
         * same magnitude as the previous global cap, now anchored at today and
         * independent of watch dates.
         */
        private const val RECGOV_MAX_POLL_WINDOW_DAYS: Int = 60
    }
}
