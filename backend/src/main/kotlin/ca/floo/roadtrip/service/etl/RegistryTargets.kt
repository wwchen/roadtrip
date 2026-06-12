package ca.floo.roadtrip.service.etl

import ca.floo.roadtrip.models.ingest.Phase
import ca.floo.roadtrip.models.ingest.Target
import ca.floo.roadtrip.models.registry.DataSourceEntry
import ca.floo.roadtrip.models.registry.PoiRegistry
import org.slf4j.LoggerFactory
import java.io.File

// Derives the IngestController target maps from config/poi-registry.yaml.
//
// Two namespaces:
//   - fetchTargets: one Target per data_sources row. Target.name is the
//     data_source slug. Used by POST /api/admin/data/fetch/<slug> and the
//     fan-out endpoint POST /api/admin/data/fetch.
//   - importTargets: one Target per poi_data row. Target.name is the
//     poi_data display name. Each Target's importPhases is a single
//     phase whose sourceName is the row's terminal etl slug; the
//     orchestrator walks the row's full etls chain when that phase runs.
//
// Adding a new vendor: append a `data_sources:` row + a `poi_data:` row
// to YAML + register the ETL adapter(s) in EtlOrchestrator.registry.
// No edits to this file. If a poi_data row's etl slugs aren't all in
// EtlOrchestrator.registry, the import target gets an empty importPhases
// list and the run is a no-op (so the YAML can declare future work).
fun fetchTargetsFromRegistry(
    registry: PoiRegistry,
    repoRoot: File,
): Map<String, Target> {
    val out = mutableMapOf<String, Target>()
    for (src in registry.dataSources) {
        out[src.slug] =
            Target(
                name = src.slug,
                fetchPhases = listOf(fetchPhaseFor(src, repoRoot)),
                importPhases = emptyList(),
            )
    }
    return out
}

fun importTargetsFromRegistry(registry: PoiRegistry): Map<String, Target> {
    val log = LoggerFactory.getLogger("RegistryTargets")
    val out = mutableMapOf<String, Target>()
    val implemented = EtlOrchestrator.registry.keys
    val implementedJoiners = EtlOrchestrator.joinerRegistry.keys

    // poi_data — produces Poi rows.
    for (row in registry.poiData) {
        val unwiredSlugs = row.etls.map { it.slug }.filterNot { it in implemented }
        val importPhases =
            if (unwiredSlugs.isEmpty()) {
                listOf(
                    Phase.Import(
                        label = "import:${row.name}",
                        name = row.name,
                        section = Phase.Import.Section.POI_DATA,
                    ),
                )
            } else {
                log.warn(
                    "poi_data '{}' has unwired etl slugs {} — import will be a no-op until adapters land",
                    row.name,
                    unwiredSlugs,
                )
                emptyList()
            }
        out[row.name] =
            Target(
                name = row.name,
                fetchPhases = emptyList(),
                importPhases = importPhases,
            )
    }

    // reservable_data — produces Reservable rows. RFC 0008.
    for (row in registry.reservableData) {
        val unwiredSlugs = row.etls.map { it.slug }.filterNot { it in implemented }
        val importPhases =
            if (unwiredSlugs.isEmpty()) {
                listOf(
                    Phase.Import(
                        label = "import:${row.name}",
                        name = row.name,
                        section = Phase.Import.Section.RESERVABLE_DATA,
                    ),
                )
            } else {
                log.warn(
                    "reservable_data '{}' has unwired etl slugs {} — import will be a no-op until adapters land",
                    row.name,
                    unwiredSlugs,
                )
                emptyList()
            }
        out[row.name] =
            Target(
                name = row.name,
                fetchPhases = emptyList(),
                importPhases = importPhases,
            )
    }

    // poi_reservable_joiner — discovers (reservable, poi) link pairs.
    // Only `enabled` rows turn into runnable phases; the rest are
    // declared-but-not-implemented and produce empty phase lists so the
    // YAML can describe future work.
    for (row in registry.poiReservableJoiners) {
        val importPhases =
            if (row.adapter in implementedJoiners) {
                listOf(
                    Phase.Import(
                        label = "import:${row.name}",
                        name = row.name,
                        section = Phase.Import.Section.POI_RESERVABLE_JOINER,
                    ),
                )
            } else {
                log.warn(
                    "poi_reservable_joiner '{}' adapter '{}' is not registered — run will be a no-op",
                    row.name,
                    row.adapter,
                )
                emptyList()
            }
        out[row.name] =
            Target(
                name = row.name,
                fetchPhases = emptyList(),
                importPhases = importPhases,
            )
    }
    return out
}

private fun fetchPhaseFor(
    src: DataSourceEntry,
    repoRoot: File,
): Phase.Fetch {
    val script = repoRoot.resolve(src.fetcher.filename).absolutePath
    val cliArgs = src.fetcher.args.flatMap { (k, v) -> listOf("--$k", v) }
    val cmd = listOf(src.fetcher.executor, script) + cliArgs
    val label = "${src.fetcher.filename.substringAfterLast('/')} ${src.slug}"
    return Phase.Fetch(label, cmd)
}
