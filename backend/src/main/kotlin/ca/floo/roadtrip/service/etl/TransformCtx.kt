package ca.floo.roadtrip.service.etl

import ca.floo.roadtrip.models.registry.PoiRegistry
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
    private val argsByEtlSlug: Map<String, Map<String, String>>,
    val rawDir: File,
) {
    /**
     * FE sub-bucket for the terminal ETL with this slug. Reads from
     * poi_data.subcategory in YAML. Null when the row omits it (categories
     * with no sub-bucket — planet-fitness, supercharger).
     */
    fun subcategoryFor(etlSlug: String): String? = subcategoryByEtlSlug[etlSlug]

    /**
     * Read a per-etl YAML arg by key (e.g. `argFor("aspira-wa-pins", "host")`
     * → "washington.goingtocamp.com"). Returns null when the key isn't set.
     */
    fun argFor(
        etlSlug: String,
        key: String,
    ): String? = argsByEtlSlug[etlSlug]?.get(key)

    companion object {
        fun load(
            rawDir: File,
            registry: PoiRegistry,
        ): TransformCtx {
            val args = mutableMapOf<String, Map<String, String>>()
            for (row in registry.poiData) {
                for (e in row.etls) {
                    args[e.slug] = e.args
                }
            }
            return TransformCtx(
                subcategoryByEtlSlug = registry.subcategoryByTerminalEtlSlug(),
                argsByEtlSlug = args,
                rawDir = rawDir,
            )
        }
    }
}
