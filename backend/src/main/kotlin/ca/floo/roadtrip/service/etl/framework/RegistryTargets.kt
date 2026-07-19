package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.metadata.ingest.Phase
import ca.floo.roadtrip.model.metadata.ingest.Target
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import org.slf4j.LoggerFactory

// Derives the IngestController target maps from the POI registry resource.
//
// Backend ingest targets are import-only: one Target per runnable poi_data
// or campsite_data row. data_sources fetchers run outside the Ktor process
// through scripts/poll_raw.py.
//
// Adding a new runnable import: append an enabled registry row and register
// the ETL adapter(s) in EtlOrchestrator.registry. If a row is disabled or its
// ETL slugs are absent from that registry, no import target is created. This
// omits only rows whose adapter is not wired into the canonical registry.
fun importTargetsFromRegistry(registry: PoiRegistry): Map<String, Target> {
    val log = LoggerFactory.getLogger("RegistryTargets")
    val out = mutableMapOf<String, Target>()
    val implemented = EtlOrchestrator.registry.keys

    // poi_data — produces canonical POI-backed catalog rows.
    for (row in registry.poiData) {
        if (!row.enabled) {
            log.info("poi_data '{}' is disabled - omitting import target", row.name)
            continue
        }
        val unwiredSlugs = row.etls.map { it.slug }.filterNot { it in implemented }
        if (unwiredSlugs.isNotEmpty()) {
            log.warn(
                "poi_data '{}' has disabled or unwired etl slugs {} - omitting import target",
                row.name,
                unwiredSlugs,
            )
            continue
        }
        out[row.name] =
            Target(
                name = row.name,
                importPhases =
                    listOf(
                        Phase.Import(
                            label = "import:${row.name}",
                            name = row.name,
                            section = Phase.Import.Section.POI_DATA,
                        ),
                    ),
            )
    }

    // campsite_data — produces canonical campsite rows linked to parent
    // campgrounds by vendor refs.
    for (row in registry.campsiteData) {
        if (!row.enabled) {
            log.info("campsite_data '{}' is disabled - omitting import target", row.name)
            continue
        }
        val unwiredSlugs = row.etls.map { it.slug }.filterNot { it in implemented }
        if (unwiredSlugs.isNotEmpty()) {
            log.warn(
                "campsite_data '{}' has disabled or unwired etl slugs {} - omitting import target",
                row.name,
                unwiredSlugs,
            )
            continue
        }
        out[row.name] =
            Target(
                name = row.name,
                importPhases =
                    listOf(
                        Phase.Import(
                            label = "import:${row.name}",
                            name = row.name,
                            section = Phase.Import.Section.CAMPSITE_DATA,
                        ),
                    ),
            )
    }

    return out
}
