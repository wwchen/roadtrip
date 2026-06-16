package ca.floo.roadtrip.service.booking

/**
 * Stable identifier per booking *vendor*. One value per upstream platform.
 *
 * Note: a single vendor can host multiple tenants (Aspira NextGen powers
 * Parks Canada, BC Parks, and Washington — same wire shape, different
 * hosts and data). Tenants are config rows, not enum values; see
 * [AspiraTenants] and [BookingProviderRegistry] for how `pois.source`
 * resolves to the right adapter instance.
 *
 * Intentionally not tied to `pois.source` (the terminal ETL slug, an
 * ingestion concept). Mapping `source -> BookingProvider` lives in the
 * registry. Keeping the two separate means the ETL layer can rename a
 * source without forcing a rename here.
 */
enum class BookingProviderId {
    RECGOV,
    ASPIRA,
    CAMIS,
}
