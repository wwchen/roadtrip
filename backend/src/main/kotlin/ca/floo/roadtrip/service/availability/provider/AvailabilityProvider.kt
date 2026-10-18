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
     * Per-day availability for the campground over the half-open window
     * `[startDate, endDate)`, as base-unit facts: one observation per
     * `(campsite, night)`, each carrying the DB id of the campsite it is about.
     *
     * [campsites] is the campsites of [campground] to cover — a campground-level
     * set. Adapters use it to resolve their vendor's site ids to DB campsite ids
     * (rec.gov's site ids, Aspira's resources) and to shape the fetch (Aspira's
     * map ids). It is not a filter and no answer is narrowed by it: narrowing an
     * answer is the caller's job, above this port, and an adapter that reduced
     * over this list would be answering a question it cannot see.
     *
     * Both callers pass the campground's set. A read serving a request passes it
     * whatever the request filtered to, because the upstream call covers the
     * campground either way: pass less and the cells of the campsites the filter
     * dropped are paid for and thrown away, and the next request calls the vendor
     * again for a campground just called. The poller arrives here with the same
     * list for a different reason — `DbAvailabilityTargetResolver.resolve(poller)`
     * resolves a poller to its POI's campsites under its `(provider, parentRef)`
     * group, so a watch scoped to one campsite still polls the catalog it sits
     * in, which is what `poller fetches the full campground catalog even when a
     * watch scopes one site` pins.
     *
     * Two rules follow from "base-unit facts", and both are the contract:
     *
     * - **Every observation names a campsite.** A vendor row an adapter cannot
     *   resolve to one of [campsites] is not a fact about a base unit; it is
     *   dropped, not returned with a null campsite id. Nothing above this port
     *   can store or serve such a row.
     * - **No answer-level derivation.** Whether a window is closed for the
     *   season, when it reopens, how many sites a stay has — all of that is
     *   derived above, from stored cells, by a caller that knows which campsites
     *   its answer is about. An adapter that reduced over [campsites] would be
     *   answering a question it cannot see.
     *
     * Call-shaping stays inside the adapter: one campground is one call here, but
     * may be several upstream (rec.gov by facility-month, Aspira per map,
     * ReserveAmerica a park matrix that can span sibling campgrounds).
     *
     * @throws AvailabilityProviderError on upstream failure (rate limit, WAF block,
     *   5xx, parse error, or unsupported capability).
     */
    suspend fun availability(
        campground: Campground,
        campsites: List<Campsite>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch

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
