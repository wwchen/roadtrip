package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
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
    /** Stable identity. Mapped from typed booking refs by the registry. */
    val id: BookingProvider

    /** Static per adapter; cheap to read and safe to surface to API clients. */
    val capabilities: AvailabilityProviderCapabilities

    override fun canHandle(key: BookingProvider): Boolean = isEnabled() && key == id

    /** Whether this provider is configured for this process. */
    fun isEnabled(): Boolean

    fun supportsCampground(campground: Campground): Boolean {
        val provider = campground.bookingProvider?.let(BookingProvider::fromIdOrNull) ?: return false
        val ref = campground.bookingProviderRef?.let { BookingProviderRef.parse(provider, it) } ?: return false
        return isEnabled() && id == ref.provider
    }

    /**
     * Derives the provider-specific [BookingProviderRef] from [campground],
     * used by surrounding infrastructure for grouping, tracing, and metadata.
     * Returns null when this provider cannot derive a ref (should not happen
     * if [supportsCampground] returned true).
     */
    fun parentRefFor(campground: Campground): BookingProviderRef? {
        val provider = campground.bookingProvider?.let(BookingProvider::fromIdOrNull) ?: return null
        return campground.bookingProviderRef?.let { BookingProviderRef.parse(provider, it) }
    }

    /**
     * Per-day availability for the half-open window `[startDate, endDate)`.
     *
     * @throws AvailabilityProviderError on upstream failure (rate limit, WAF block,
     *   5xx, parse error, or unsupported capability).
     */
    suspend fun availability(
        campground: Campground,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch

    /**
     * POI-scoped availability narrowed to the campsites linked to the POI.
     * Most providers can answer from the campground-level endpoint, so the
     * default delegates to [availability]. Providers with a parent/child map
     * split can override this to classify the actual linked resources.
     */
    suspend fun catalogAvailability(
        campground: Campground,
        campsites: List<Campsite>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch = availability(campground, startDate, endDate)

    fun vendorSiteIdFor(campsite: Campsite): String =
        campsite.bookingProviderRef
            ?.takeIf { campsite.bookingProvider == id.id }
            ?: campsite.dataProviderRef.serialize()

    /**
     * User-facing reservation URL *template* for [campsite] under a campground
     * whose parent scope is [parentRef], or null when this provider exposes no
     * stable deep link. The template may embed the [ReservationUrlTemplate]
     * placeholders (filled by the caller for a chosen window) or be a static URL.
     * Pure and cheap — no upstream call, no throw.
     */
    fun reservationUrlTemplate(
        campsite: Campsite,
        parentRef: BookingProviderRef,
    ): String? = null

    fun reservationUrl(
        campsite: Campsite,
        parentRef: BookingProviderRef,
        date: LocalDate,
    ): String? =
        reservationUrlTemplate(campsite, parentRef)?.let {
            ReservationUrlTemplate.fill(it, date, date.plusDays(1))
        }
}
