package ca.floo.roadtrip.service.availability.provider.adapters.aspira

/**
 * Per-tenant Aspira NextGen configuration. One row per upstream host.
 *
 * Aspira runs the same SPA build behind every tenant — see
 * `docs/reservation-providers/aspira.md`. The wire shape is identical;
 * only the host, the data, and a few presentation details differ. So
 * adding a new tenant (Ontario, Quebec, etc.) is one row here, not a
 * new enum value, adapter, or registry branch.
 *
 * The `vendorCode` is what gets stamped into the canonical campsite's
 * provider `vendor` for sites under that tenant. Provider vendors disallow ':', so
 * use underscore-separated tenant codes (`aspira_pc`, `aspira_bc`, …).
 *
 * `bookingHorizonDays` is the rolling booking window the upstream
 * exposes. Eventually this should come from each tenant's
 * `/api/dateschedule/resourcelocationid` response so changes don't
 * require a deploy; for now it's a per-tenant constant since the
 * value is stable and identical across tenants.
 */
data class AspiraTenant(
    val host: String,
    val vendorCode: String,
    val bookingHorizonDays: Int,
    val bookingSystemLabel: String = "Aspira NextGen",
    val ctaLabel: String? = null,
)
