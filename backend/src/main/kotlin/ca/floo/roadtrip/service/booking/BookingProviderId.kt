package ca.floo.roadtrip.service.booking

/**
 * Stable identifier per booking upstream. One value per *adapter instance* —
 * Aspira NextGen runs three of them (PC, BC, WA), each with its own host, so
 * each gets its own id even though they share an adapter class.
 *
 * Intentionally not tied to `pois.source` (which is the terminal ETL slug, an
 * ingestion concept). Mapping `source -> BookingProviderId` lives in the
 * registry. Keeping the two separate means the ETL layer can rename a source
 * without forcing a rename here.
 */
enum class BookingProviderId {
    RECGOV,
    ASPIRA_PC,
    ASPIRA_BC,
    ASPIRA_WA,
    CAMIS,
}
