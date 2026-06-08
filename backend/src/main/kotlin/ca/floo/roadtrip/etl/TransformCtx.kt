package ca.floo.roadtrip.etl

import ca.floo.roadtrip.etl.registry.PoiRegistry
import java.io.File

// Read-only context handed to ETL transformers. Today: just the raw-capture
// directory + a per-terminal-etl subcategory lookup sourced from the YAML.
//
// Booking provider info is no longer here — the booking_provider table
// (and its FK column) was dropped (V8) because the dispatch path the FK
// was meant to power never landed; the FE reads pois.provider_ref (JSONB)
// directly. ETLs that need a ProviderRef just construct the sealed variant
// directly with values from their args:.
class TransformCtx private constructor(
    private val subcategoryByEtlSlug: Map<String, String?>,
    val rawDir: File,
) {
    /**
     * FE sub-bucket for the terminal ETL with this slug. Reads from
     * poi_data.subcategory in YAML. Null when the row omits it (categories
     * with no sub-bucket — planet-fitness, supercharger).
     */
    fun subcategoryFor(etlSlug: String): String? = subcategoryByEtlSlug[etlSlug]

    companion object {
        fun load(
            rawDir: File,
            registry: PoiRegistry,
        ): TransformCtx = TransformCtx(registry.subcategoryByTerminalEtlSlug(), rawDir)
    }
}
