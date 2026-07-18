package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.metadata.registry.AgencyConfig
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import java.io.File

// Read-only context handed to ETL transformers. Today: the raw-capture
// directory plus per-terminal-etl metadata sourced from the YAML.
class TransformCtx private constructor(
    private val subcategoryByEtlSlug: Map<String, String?>,
    private val agencyByEtlSlug: Map<String, AgencyConfig?>,
    private val argsByEtlSlug: Map<String, Map<String, String>>,
    val rawDir: File,
) {
    /**
     * FE sub-bucket for the terminal ETL with this slug. Reads from
     * poi_data.subcategory in YAML. Null when the row omits it (categories
     * with no sub-bucket — planet-fitness, supercharger).
     */
    fun subcategoryFor(etlSlug: String): String? = subcategoryByEtlSlug[etlSlug]

    fun agencyFor(etlSlug: String): AgencyConfig? = agencyByEtlSlug[etlSlug]

    fun requiredConstantAgency(etlSlug: String): String =
        when (val agency = agencyFor(etlSlug)) {
            is AgencyConfig.Constant -> agency.value
            is AgencyConfig.DerivedFromField ->
                error("$etlSlug uses poi_data.agency.${AgencyConfig.DERIVED_FROM_FIELD_KEY}='${agency.field}', not a constant agency")
            null -> error("$etlSlug is missing required poi_data.agency")
        }

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
                agencyByEtlSlug = registry.agencyByTerminalEtlSlug(),
                argsByEtlSlug = args,
                rawDir = rawDir,
            )
        }
    }
}
