package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import java.time.LocalDate

/**
 * Primary port for "give me this campground's availability." One adapter per
 * upstream reservation system (rec.gov, Aspira NextGen instance, Camis, …).
 *
 * Routes consume this interface; they never branch on `ProviderRef` variant
 * directly. See `docs/booking-providers.md` for the architecture rules.
 *
 * Adapters own:
 *   - their own caching (per-month, per-host, however the upstream wants)
 *   - vendor-specific error translation into [BookingProviderError]
 *   - the host / API root they talk to (set at construction time)
 *
 * Adapters do NOT own:
 *   - poll cadence (the platform poller does — see RFC 0007)
 *   - rate-limit accounting (cross-adapter; lives above the port)
 *   - HTTP response shaping (routes do — adapter returns a typed DTO)
 */
interface BookingProvider {
    /** Stable identity. Mapped from `pois.source` + `provider_ref` shape by the registry. */
    val id: BookingProviderId

    /** Static per adapter; cheap to read. Surfaced via `/api/campsite/capabilities/{poi_id}`. */
    val capabilities: BookingCapabilities

    /**
     * Per-day availability for the inclusive window `[start, start + days - 1]`.
     *
     * @throws BookingProviderError on upstream failure (rate limit, WAF block,
     *   5xx, parse error, or unsupported capability).
     */
    suspend fun availability(req: AvailabilityRequest): AvailabilityResponseDto

    /**
     * Just the dates inside the requested window where at least one site is
     * bookable. Cheaper variant for the bulk "score N campgrounds" endpoint;
     * adapters typically share the cache with [availability].
     *
     * @throws BookingProviderError on upstream failure.
     */
    suspend fun availableDates(req: AvailableDatesRequest): List<String>
}

/**
 * Single-id availability request.
 *
 * `minNights` controls same-site multi-night classification: a day D in the
 * response is "available" iff at least one site is open for all N nights
 * starting D. Default 1 collapses to single-night classification.
 *
 * `force=true` busts the adapter's cache.
 */
data class AvailabilityRequest(
    val ref: ProviderRef,
    val start: LocalDate,
    val days: Int,
    val minNights: Int = 1,
    val force: Boolean = false,
)

/** Bulk-score request. Same window semantics as [AvailabilityRequest], no force. */
data class AvailableDatesRequest(
    val ref: ProviderRef,
    val start: LocalDate,
    val nights: Int,
)
