package ca.floo.roadtrip.service.reservation

import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.service.api.AvailabilityObservationBatch
import java.time.LocalDate

/**
 * Primary port for "give me this campground's availability." One adapter per
 * upstream reservation system (rec.gov, Aspira NextGen instance, Camis, …).
 *
 * Routes consume this interface; they never branch on `ProviderRef` variant
 * directly. See `docs/reservation-providers.md` for the architecture rules.
 *
 * Adapters own:
 *   - their own caching (per-month, per-host, however the upstream wants)
 *   - vendor-specific error translation into [ReservationProviderError]
 *   - the host / API root they talk to (set at construction time)
 *
 * Adapters do NOT own:
 *   - poll cadence (the platform poller does — see RFC 0007)
 *   - rate-limit accounting (cross-adapter; lives above the port)
 *   - HTTP response shaping (service/API layer rolls observations into DTOs)
 */
interface ReservationProvider {
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
    suspend fun availability(req: AvailabilityRequest): AvailabilityObservationBatch

    /**
     * POI-scoped availability narrowed to the catalog rows linked to the POI.
     * Most providers can answer from the campground-level endpoint, so the
     * default delegates to [availability]. Providers with a parent/child map
     * split can override this to classify the actual linked resources.
     */
    suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityObservationBatch =
        availability(
            AvailabilityRequest(
                ref = req.ref,
                startDate = req.startDate,
                endDate = req.endDate,
                force = req.force,
            ),
        )

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
    suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityObservationBatch =
        throw ReservationProviderError.Unsupported("reservableAvailability", id)
}

/**
 * Single-id availability request.
 *
 * `force=true` busts the adapter's cache.
 */
data class AvailabilityRequest(
    val ref: ProviderRef,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val force: Boolean = false,
)

data class CatalogAvailabilityRequest(
    val ref: ProviderRef,
    val reservables: List<CatalogReservableRef>,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val force: Boolean = false,
)

data class CatalogReservableRef(
    val rid: String,
    val vendorId: String,
    val mapId: Long? = null,
    val resourceLocationId: Long? = null,
)

/**
 * Single-reservable availability request. [vendorId] is the opaque
 * reservables.vendor_id for the adapter's upstream (rec.gov campsite id,
 * Aspira resource id, etc.).
 */
data class ReservableAvailabilityRequest(
    val ref: ProviderRef,
    val vendorId: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val force: Boolean = false,
)
