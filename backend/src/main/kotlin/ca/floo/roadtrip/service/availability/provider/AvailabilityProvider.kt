package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
import ca.floo.roadtrip.model.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.support.Dispatchable
import java.time.LocalDate

/**
 * Primary availability-provider port. One adapter per upstream booking or
 * availability platform (rec.gov, Aspira NextGen instance, ReserveAmerica, ...).
 *
 * Availability services consume this interface; routes stay at the HTTP
 * boundary and never branch on `BookingProviderRef` variants directly. See
 * `docs/reservation-providers.md` for the architecture rules.
 *
 * Adapters own:
 *   - their own caching (per-month, per-host, however the upstream wants)
 *   - vendor-specific error translation into [AvailabilityProviderError]
 *   - the host / API root they talk to (set at construction time)
 *
 * Adapters do NOT own:
 *   - poll cadence (the platform poller does — see RFC 0007)
 *   - rate-limit accounting (cross-adapter; lives above the port)
 *   - HTTP response shaping (service/API layer rolls observations into DTOs)
 */
interface AvailabilityProvider : Dispatchable<BookingProvider> {
    /** Stable identity. Mapped from catalog source slug + `provider_ref` shape by the registry. */
    val id: BookingProvider

    /** Static per adapter; cheap to read and safe to surface to API clients. */
    val capabilities: AvailabilityProviderCapabilities

    override fun canHandle(key: BookingProvider): Boolean = isEnabled() && key == id

    /** Whether this provider is configured for this process. */
    fun isEnabled(): Boolean

    /**
     * Whether this adapter can serve the typed provider reference for this
     * process. The registry calls this before dispatching so unconfigured
     * providers can decline and the availability resolver can try linked
     * fallback refs without hardcoded provider branching.
     */
    fun supportsRef(ref: BookingProviderRef): Boolean = isEnabled() && id == ref.bookingProvider()

    /**
     * Per-day availability for the half-open window `[startDate, endDate)`.
     *
     * @throws AvailabilityProviderError on upstream failure (rate limit, WAF block,
     *   5xx, parse error, or unsupported capability).
     */
    suspend fun availability(
        ref: BookingProviderRef,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch

    /**
     * POI-scoped availability narrowed to the catalog rows linked to the POI.
     * Most providers can answer from the campground-level endpoint, so the
     * default delegates to [availability]. Providers with a parent/child map
     * split can override this to classify the actual linked resources.
     */
    suspend fun catalogAvailability(
        ref: BookingProviderRef,
        campsites: List<CatalogCampsiteRef>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch = availability(ref, startDate, endDate)

    /**
     * User-facing reservation URL *template* for [campsite] under a campground
     * whose parent scope is [parentRef], or null when this provider exposes no
     * stable deep link. The template may embed the
     * [ReservationUrlTemplate] placeholders (filled by the caller for a chosen
     * window) or be a static URL. Pure and cheap — no upstream call, no throw.
     *
     * The URL scheme is vendor-specific, so it lives in the adapter — the one
     * place that knows the vendor's reservation-site shape. Both the campsites
     * API (which ships the template to the web app) and provider-neutral
     * callers (alert notifications, via [reservationUrl]) read it from here rather
     * than hardcoding vendor URLs. Default null keeps deep links opt-in per
     * adapter — a provider without one is not a gap to fill.
     */
    fun reservationUrlTemplate(
        campsite: CampsiteAvailabilityTarget,
        parentRef: BookingProviderRef,
    ): String? = null

    /**
     * Concrete reservation deep link for [campsite] on the single night beginning
     * [date] (check-out the next day), or null when the provider exposes none.
     * Derived from [reservationUrlTemplate] by filling its window placeholders, so
     * an adapter only implements the template once.
     */
    fun reservationUrl(
        campsite: CampsiteAvailabilityTarget,
        parentRef: BookingProviderRef,
        date: LocalDate,
    ): String? = reservationUrlTemplate(campsite, parentRef)?.let { ReservationUrlTemplate.fill(it, date, date.plusDays(1)) }
}
