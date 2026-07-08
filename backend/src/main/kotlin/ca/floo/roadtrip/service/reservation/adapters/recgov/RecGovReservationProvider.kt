package ca.floo.roadtrip.service.reservation.adapters.recgov

import ca.floo.roadtrip.clients.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.service.reservation.AvailabilityClient
import ca.floo.roadtrip.service.reservation.CapabilityLimit
import ca.floo.roadtrip.service.reservation.CapabilityTimeUnit
import ca.floo.roadtrip.service.reservation.CatalogReservableRef
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import java.time.LocalDate

/**
 * rec.gov adapter. Vendor-specific error translation lives here; routes only
 * see [ReservationProviderError]. Deciding whether to serve stored data or call
 * this adapter live is handled above it by
 * [ca.floo.roadtrip.service.api.AvailabilityLoader], reading current state from
 * the `availability` interval table.
 */
class RecGovReservationProvider(
    private val client: RecGovAvailabilityClient,
) : ReservationProvider,
    AvailabilityClient {
    override val id: ReservationProviderId = ReservationProviderId.RECGOV

    override val capabilities: ReservationProviderCapabilities =
        ReservationProviderCapabilities(
            supportsAvailability = true,
            supportsAlerts = true,
            maxPollWindowDays = RECGOV_MAX_POLL_WINDOW_DAYS,
            bookingHorizon = CapabilityLimit(RECGOV_BOOKING_HORIZON_MONTHS, CapabilityTimeUnit.MONTH),
            fetchWindowCap = CapabilityLimit(RECGOV_FETCH_WINDOW_MONTHS, CapabilityTimeUnit.MONTH),
        )

    override suspend fun availability(
        ref: ProviderRef,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val recgovId = recgovIdOrThrow(ref)
        return runWithErrorMapping {
            fetchRecgovAvailabilityObservations(
                client = client,
                recgovId = recgovId,
                startDate = startDate,
                endDate = endDate,
            )
        }
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
        val recgovId = recgovIdOrThrow(ref)
        return runWithErrorMapping {
            fetchRecgovCatalogObservations(
                client = client,
                recgovId = recgovId,
                campsiteIds = reservables.map { it.vendorId }.toSet(),
                startDate = startDate,
                endDate = endDate,
            )
        }
    }

    override suspend fun reservableAvailability(
        ref: ProviderRef,
        vendorId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val recgovId = recgovIdOrThrow(ref)
        return runWithErrorMapping {
            fetchRecgovReservableObservations(
                client = client,
                recgovId = recgovId,
                campsiteId = vendorId,
                startDate = startDate,
                endDate = endDate,
            )
        }
    }

    override fun availabilityFetchCost(
        startDate: LocalDate,
        endDate: LocalDate,
    ): Long {
        if (!endDate.isAfter(startDate)) return 0L
        return monthsCovering(startDate, endDate.minusDays(1)).size.toLong()
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
        private const val RECGOV_BOOKING_HORIZON_MONTHS: Int = 6

        /**
         * Widest single-tick poll window. rec.gov shapes availability calls by
         * calendar month, so 60 days keeps a tick to ~2-3 month calls — the
         * same magnitude as the previous global cap, now anchored at today and
         * independent of watch dates.
         */
        private const val RECGOV_MAX_POLL_WINDOW_DAYS: Int = 60
        private const val RECGOV_FETCH_WINDOW_MONTHS: Int = 1
    }
}
