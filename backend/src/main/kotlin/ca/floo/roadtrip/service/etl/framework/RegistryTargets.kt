package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.models.metadata.ingest.Phase
import ca.floo.roadtrip.models.metadata.ingest.Target
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import org.slf4j.LoggerFactory

// Derives the IngestController target maps from the POI registry resource.
//
// Backend ingest targets are import-only: one Target per runnable poi_data,
// campsite_data, or campsite_parent_joiner row. data_sources fetchers run
// outside the Ktor process through scripts/poll_raw.py.
//
// Adding a new runnable import: append an enabled registry row and register
// the ETL adapter(s) in EtlOrchestrator.registry. If a row is disabled or its
// ETL slugs are absent from that registry, no import target is created. This
// omits only rows whose adapter is not wired into the canonical registry.
fun importTargetsFromRegistry(registry: PoiRegistry): Map<String, Target> {
    val log = LoggerFactory.getLogger("RegistryTargets")
    val out = mutableMapOf<String, Target>()
    val implemented = EtlOrchestrator.registry.keys
    val implementedJoiners = EtlOrchestrator.joinerRegistry.keys

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

    // campsite_parent_joiner — reconciliation pass. Reparents any campsite
    // whose current campground_id disagrees with the joiner's cross-vendor
    // lookup; idempotent on already-correct rows. See runJoiner.
    for (row in registry.campsiteParentJoiners) {
        if (!row.enabled) {
            log.info("campsite_parent_joiner '{}' is disabled - omitting import target", row.name)
            continue
        }
        if (row.adapter !in implementedJoiners) {
            log.warn(
                "campsite_parent_joiner '{}' adapter '{}' is disabled or not registered - omitting import target",
                row.name,
                row.adapter,
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
                            section = Phase.Import.Section.CAMPSITE_PARENT_JOINER,
                        ),
                    ),
            )
    }
    return out
}
