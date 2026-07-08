package ca.floo.roadtrip.service.reservation.adapters.aspira

import ca.floo.roadtrip.service.reservation.CapabilityLimit
import java.time.temporal.ChronoUnit

/**
 * Per-tenant Aspira NextGen configuration. One row per upstream host.
 *
 * Aspira runs the same SPA build behind every tenant — see
 * `docs/reservation-providers/aspira.md`. The wire shape is identical;
 * only the host, the data, and a few presentation details differ. So
 * adding a new tenant (Ontario, Quebec, etc.) is one row here, not a
 * new enum value, adapter, or registry branch.
 *
 * The `vendorCode` is what gets stamped into [ca.floo.roadtrip.models.domain.ReservableId.vendor]
 * for sites under that tenant. ReservableId disallows ':' in vendor, so
 * use underscore-separated tenant codes (`aspira_pc`, `aspira_bc`, …).
 *
 * `bookingHorizon` is the rolling booking window the upstream exposes.
 * Eventually this should come from each tenant's
 * `/api/dateschedule/resourcelocationid` response so changes don't
 * require a deploy; for now it's a per-tenant constant since the
 * value is stable and identical across tenants.
 */
data class AspiraTenant(
    val host: String,
    val vendorCode: String,
    val bookingHorizon: CapabilityLimit,
    val bookingSystemLabel: String = "Aspira NextGen",
    val ctaLabel: String? = null,
)

object AspiraTenants {
    /** Aspira NextGen typical horizon. */
    private val DEFAULT_BOOKING_HORIZON: CapabilityLimit = CapabilityLimit(365, ChronoUnit.DAYS)

    /**
     * The tenant table. Order does not matter; lookup is by host.
     *
     * Adding a tenant: append a row. Validation at boot
     * ([ReservationProviderRegistryFactory]) ensures every host the YAML
     * registry declares has a row here, so a forgotten entry fails
     * loudly instead of silently routing to a missing adapter.
     */
    private val ALL: List<AspiraTenant> =
        listOf(
            AspiraTenant(
                host = "reservation.pc.gc.ca",
                vendorCode = "aspira_pc",
                bookingHorizon = DEFAULT_BOOKING_HORIZON,
                bookingSystemLabel = "Aspira NextGen (Parks Canada)",
                ctaLabel = "Reserve on parks.canada.ca",
            ),
            AspiraTenant(
                host = "camping.bcparks.ca",
                vendorCode = "aspira_bc",
                bookingHorizon = DEFAULT_BOOKING_HORIZON,
                bookingSystemLabel = "Aspira NextGen (BC Parks)",
                ctaLabel = "Book on BC Parks",
            ),
            AspiraTenant(
                host = "washington.goingtocamp.com",
                vendorCode = "aspira_wa",
                bookingHorizon = DEFAULT_BOOKING_HORIZON,
                bookingSystemLabel = "Aspira NextGen (WA State Parks)",
                ctaLabel = "Book WA State Park",
            ),
        )

    private val BY_HOST: Map<String, AspiraTenant> = ALL.associateBy { it.host }
    private val BY_VENDOR_CODE: Map<String, AspiraTenant> = ALL.associateBy { it.vendorCode }

    fun byHost(host: String): AspiraTenant? = BY_HOST[host]

    /** Tenant for a reservable vendor code stamped under that tenant (e.g. `aspira_wa`). */
    fun byVendorCode(vendorCode: String): AspiraTenant? = BY_VENDOR_CODE[vendorCode]

    fun knownHosts(): Set<String> = BY_HOST.keys
}
