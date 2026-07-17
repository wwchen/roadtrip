package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
import ca.floo.roadtrip.model.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.model.domain.ProviderRef
import java.time.LocalDate

/**
 * rec.gov adapter. Vendor-specific error translation lives here; routes only
 * see [AvailabilityProviderError]. Deciding whether to serve stored data or call
 * this adapter live is handled above it by
 * [ca.floo.roadtrip.service.api.AvailabilityLoader], reading current state from
 * the `availability` interval table.
 */
class RecGovAvailabilityProvider(
    private val client: RecGovAvailabilityClient,
    private val enabled: Boolean,
) : AvailabilityProvider {
    override val id: AvailabilityProviderId = AvailabilityProviderId.RECGOV

    override val capabilities: AvailabilityProviderCapabilities =
        AvailabilityProviderCapabilities(
            supportsInternalPolling = true,
            bookingHorizonDays = RECGOV_BOOKING_HORIZON_DAYS,
            maxPollWindowDays = RECGOV_MAX_POLL_WINDOW_DAYS,
        )

    override fun isEnabled(): Boolean = enabled

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
        campsites: List<CatalogCampsiteRef>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        if (campsites.isEmpty()) {
            return availability(ref, startDate, endDate)
        }
        val recgovId = recgovIdOrThrow(ref)
        return runWithErrorMapping {
            fetchRecgovCatalogObservations(
                client = client,
                recgovId = recgovId,
                campsites = campsites,
                startDate = startDate,
                endDate = endDate,
            )
        }
    }

    /** rec.gov single-site booking page; the concrete-date [bookingUrl] fills
     *  the window placeholders. [parentRef] is unused — the site id alone
     *  addresses the page. */
    override fun reservationUrlTemplate(
        campsite: CampsiteAvailabilityTarget,
        parentRef: ProviderRef,
    ): String = RecGovBookingUrl.template(campsite.vendorId)

    private fun recgovIdOrThrow(ref: ProviderRef): String =
        when (ref) {
            is ProviderRef.RecGov -> ref.recgovId
            else -> throw AvailabilityProviderError.WrongRefType(id.name.lowercase(), ref::class.simpleName ?: "unknown")
        }

    private inline fun <T> runWithErrorMapping(block: () -> T): T =
        try {
            block()
        } catch (e: AvailabilityProviderError) {
            throw e
        } catch (e: Exception) {
            // The recgov client's exception types aren't a single hierarchy
            // (some throw plain Exception with rate-limit text). Pattern-
            // match on message text the same way the legacy mapper did, but
            // produce typed AvailabilityProviderError so the route doesn't need
            // to know the upstream's quirks.
            val msg = e.message.orEmpty()
            when {
                msg.contains("429") || msg.contains("rate") -> throw AvailabilityProviderError.RateLimited(e)
                else -> throw AvailabilityProviderError.UpstreamUnavailable(e)
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
