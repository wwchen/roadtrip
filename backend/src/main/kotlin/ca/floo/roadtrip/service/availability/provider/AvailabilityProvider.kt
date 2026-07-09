package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.domain.Campsite
import ca.floo.roadtrip.models.domain.ProviderRef
import java.time.LocalDate

/**
 * Primary availability-provider port. One adapter per upstream booking or
 * availability platform (rec.gov, Aspira NextGen instance, ReserveAmerica, ...).
 *
 * Availability services consume this interface; routes stay at the HTTP
 * boundary and never branch on `ProviderRef` variants directly. See
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
interface AvailabilityProvider : AvailabilityClient {
    /** Stable identity. Mapped from `pois.source` + `provider_ref` shape by the registry. */
    val id: AvailabilityProviderId

    /** Static per adapter; cheap to read and safe to surface to API clients. */
    val capabilities: AvailabilityProviderCapabilities

    /**
     * Whether this adapter can serve the typed provider reference for this
     * process. The registry calls this before dispatching so unconfigured
     * providers can decline and the availability resolver can try linked
     * fallback refs without hardcoded provider branching.
     */
    fun canHandle(ref: ProviderRef): Boolean = id == ref.availabilityProviderId()

    /**
     * Per-day availability for the half-open window `[startDate, endDate)`.
     *
     * @throws AvailabilityProviderError on upstream failure (rate limit, WAF block,
     *   5xx, parse error, or unsupported capability).
     */
    override suspend fun availability(
        ref: ProviderRef,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch

    /**
     * POI-scoped availability narrowed to the catalog rows linked to the POI.
     * Most providers can answer from the campground-level endpoint, so the
     * default delegates to [availability]. Providers with a parent/child map
     * split can override this to classify the actual linked resources.
     */
    override suspend fun catalogAvailability(
        ref: ProviderRef,
        campsites: List<CatalogCampsiteRef>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch = availability(ref, startDate, endDate)

    /**
     * User-facing booking URL *template* for [reservable] under a campground
     * whose parent scope is [parentRef], or null when this provider exposes no
     * stable deep link. The template may embed the
     * [BookingUrlTemplate] placeholders (filled by the caller for a chosen
     * window) or be a static URL. Pure and cheap — no upstream call, no throw.
     *
     * The URL scheme is vendor-specific, so it lives in the adapter — the one
     * place that knows the vendor's booking-site shape. Both the reservables
     * API (which ships the template to the web app) and provider-neutral
     * callers (alert notifications, via [bookingUrl]) read it from here rather
     * than hardcoding vendor URLs. Default null keeps deep links opt-in per
     * adapter — a provider without one is not a gap to fill.
     */
    fun bookingUrlTemplate(
        campsite: Campsite,
        parentRef: ProviderRef,
    ): String? = null

    /**
     * Concrete booking deep link for [campsite] on the single night beginning
     * [date] (check-out the next day), or null when the provider exposes none.
     * Derived from [bookingUrlTemplate] by filling its window placeholders, so
     * an adapter only implements the template once.
     */
    fun bookingUrl(
        campsite: Campsite,
        parentRef: ProviderRef,
        date: LocalDate,
    ): String? = bookingUrlTemplate(campsite, parentRef)?.let { BookingUrlTemplate.fill(it, date, date.plusDays(1)) }
}

data class CatalogCampsiteRef(
    val campsiteId: Long,
    val vendorId: String,
    val mapId: Long? = null,
    val resourceLocationId: Long? = null,
)
