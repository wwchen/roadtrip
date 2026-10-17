package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.bookingRefFor
import ca.floo.roadtrip.model.domain.provider.BookingAlias
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
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
interface AvailabilityProvider {
    /** Stable identity. Mapped from typed booking refs by the registry. */
    val id: BookingProvider

    /** Static per adapter; cheap to read and safe to surface to API clients. */
    val capabilities: AvailabilityProviderCapabilities

    /** Whether this provider is configured for this process. */
    fun isEnabled(): Boolean

    /**
     * This provider's identity for [campground]: its primary booking ref when
     * that ref is this provider's, else the matching [BookingAlias] parsed as
     * this provider's ref. Null when the campground names this provider
     * nowhere. One rule for every provider — a Campflare row that rec.gov also
     * sells is claimed by both, through the primary and the alias respectively.
     */
    fun claimedRef(campground: Campground): BookingProviderRef? = campground.bookingRefFor(id)

    fun supportsCampground(campground: Campground): Boolean = isEnabled() && claimedRef(campground) != null

    /**
     * Derives the provider-specific [BookingProviderRef] from [campground],
     * used by surrounding infrastructure for grouping, tracing, and metadata.
     * Returns null when this provider cannot derive a ref (should not happen
     * if [supportsCampground] returned true).
     */
    fun parentRefFor(campground: Campground): BookingProviderRef? = claimedRef(campground)

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
     * POI-scoped availability for [scope]. Adapters override this; callers use
     * the [catalogAvailability] entry point instead, which is what turns the
     * batch's per-campsite material into the answer-level facts.
     *
     * An override answers for `scope.answerFor` and may key cells for anything
     * in `scope.covered` that its upstream response already carries — never for
     * a campsite it did not ask the vendor about, because a cell written on no
     * question claims coverage the store will later serve.
     *
     * Most providers can answer from the campground-level endpoint, so the
     * default delegates to [availability]. Providers with a parent/child map
     * split can override this to classify the actual linked resources.
     */
    suspend fun fetchCatalogAvailability(
        campground: Campground,
        scope: CatalogAvailabilityScope,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch = availability(campground, startDate, endDate)

    /** The campsite's id on this vendor: its primary, else this provider's alias, else the catalog ref. */
    fun vendorSiteIdFor(campsite: Campsite): String =
        campsite.bookingProviderRef
            ?.takeIf { campsite.bookingProvider == id.id }
            ?: campsite.bookingAliases.firstOrNull { it.provider == id }?.ref
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

/**
 * Catalog availability for [scope] — the entry point every caller uses.
 *
 * Deliberately not a member of [AvailabilityProvider]: no adapter can override
 * it, so no adapter decides for itself what its answer says about the campsites
 * the caller filtered out. The season block is reduced here, from the
 * per-campsite material the batch carries, over `scope.answerFor` — so a batch
 * that covers a whole campground cannot tell a caller asking about cabins when
 * the tents reopen.
 *
 * @throws ca.floo.roadtrip.model.availability.AvailabilityProviderError on upstream failure.
 */
suspend fun AvailabilityProvider.catalogAvailability(
    campground: Campground,
    scope: CatalogAvailabilityScope,
    startDate: LocalDate,
    endDate: LocalDate,
): AvailabilityObservationBatch {
    val batch = fetchCatalogAvailability(campground, scope, startDate, endDate)
    // No per-campsite material means the adapter told campsites apart nowhere —
    // the campground-wide answer the default delegate gives — so the block it
    // came back with is the only one there is.
    if (batch.reopensOnByCampsite.isEmpty()) return batch
    return batch.copy(seasonBlock = scope.seasonBlockFrom(batch.reopensOnByCampsite))
}
