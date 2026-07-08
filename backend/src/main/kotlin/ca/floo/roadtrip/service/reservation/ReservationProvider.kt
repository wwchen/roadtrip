package ca.floo.roadtrip.service.reservation

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
import java.time.LocalDate

/**
 * Primary reservation-provider port. One adapter per upstream reservation
 * system (rec.gov, Aspira NextGen instance, ReserveAmerica, …).
 *
 * Availability services consume this interface; routes stay at the HTTP
 * boundary and never branch on `ProviderRef` variants directly. See
 * `docs/reservation-providers.md` for the architecture rules.
 *
 * Adapters own:
 *   - their own caching (per-month, per-host, however the upstream wants)
 *   - vendor-specific error translation into [ReservationProviderError]
 *   - the host / API root they talk to (set at construction time)
 *   - the cost hint for how many upstream availability requests a logical
 *     provider call fans out to
 *
 * Adapters do NOT own:
 *   - poll cadence (the platform poller does — see RFC 0007)
 *   - rate-limit accounting (cross-adapter; lives above the port)
 *   - HTTP response shaping (service/API layer rolls observations into DTOs)
 */
interface ReservationProvider : AvailabilityClient {
    /** Stable identity. Mapped from `pois.source` + `provider_ref` shape by the registry. */
    val id: ReservationProviderId

    /** Static per adapter; cheap to read and safe to surface to API clients. */
    val capabilities: ReservationProviderCapabilities

    /**
     * Per-day availability for the half-open window `[startDate, endDate)`.
     *
     * @throws ReservationProviderError on upstream failure (rate limit, WAF block,
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
        reservables: List<CatalogReservableRef>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch = availability(ref, startDate, endDate)

    /**
     * Per-day availability for one reservable under a campground. Providers
     * should share the same upstream cache as [availability]; this endpoint is
     * a narrower projection of the same inventory window, not a second
     * independent polling path.
     *
     * Default is unsupported so adapters can opt in as their upstream exposes
     * stable per-resource status.
     *
     * @throws ReservationProviderError on upstream failure or unsupported provider.
     */
    override suspend fun reservableAvailability(
        ref: ProviderRef,
        vendorId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch = throw ReservationProviderError.Unsupported("reservableAvailability", id)

    /**
     * Number of upstream availability requests consumed by one logical
     * availability call for `[startDate, endDate)`. The poller performs the
     * accounting above this port, but the adapter owns the upstream request
     * shape. Most providers make one request; Rec.gov overrides this because
     * its campground endpoint is calendar-month shaped.
     */
    fun availabilityFetchCost(
        startDate: LocalDate,
        endDate: LocalDate,
    ): Long = DEFAULT_AVAILABILITY_FETCH_COST

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
        reservable: Reservable,
        parentRef: ProviderRef,
    ): String? = null

    /**
     * Concrete booking deep link for [reservable] on the single night beginning
     * [date] (check-out the next day), or null when the provider exposes none.
     * Derived from [bookingUrlTemplate] by filling its window placeholders, so
     * an adapter only implements the template once.
     */
    fun bookingUrl(
        reservable: Reservable,
        parentRef: ProviderRef,
        date: LocalDate,
    ): String? = bookingUrlTemplate(reservable, parentRef)?.let { BookingUrlTemplate.fill(it, date, date.plusDays(1)) }
}

data class CatalogReservableRef(
    val rid: String,
    val vendorId: String,
    val mapId: Long? = null,
    val resourceLocationId: Long? = null,
)

private const val DEFAULT_AVAILABILITY_FETCH_COST: Long = 1L
