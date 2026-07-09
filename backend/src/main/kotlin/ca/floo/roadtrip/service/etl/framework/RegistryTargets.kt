package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.models.metadata.ingest.Phase
import ca.floo.roadtrip.models.metadata.ingest.Target
import ca.floo.roadtrip.models.metadata.registry.DataSourceEntry
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import org.slf4j.LoggerFactory
import java.io.File

// Derives the IngestController target maps from config/poi-registry.yaml.
//
// Two namespaces:
//   - fetchTargets: one Target per data_sources row. Target.name is the
//     data_source slug. Used by POST /api/admin/data/fetch/<slug> and the
//     fan-out endpoint POST /api/admin/data/fetch.
//   - importTargets: one Target per poi_data, reservable_data, and
//     poi_reservable_joiner row. Map insertion order is the fan-out order:
//     POIs first, reservables second, joiners last.
//
// Adding a new runnable import: append a registry row and register the ETL
// adapter(s) in EtlOrchestrator.registry. If a row's ETL slugs are absent
// from that registry, the import target gets an empty importPhases list and
// the run is a no-op. This is how legacy vendor rows stay declared but
// disabled while their adapters are migrated to canonical catalog writes.
fun fetchTargetsFromRegistry(
    registry: PoiRegistry,
    repoRoot: File,
): Map<String, Target> {
    val out = mutableMapOf<String, Target>()
    for (src in orderedDataSources(registry.dataSources)) {
        out[src.slug] =
            Target(
                name = src.slug,
                fetchPhases = listOf(fetchPhaseFor(src, repoRoot)),
                importPhases = emptyList(),
            )
    }
    return out
}

private fun orderedDataSources(sources: List<DataSourceEntry>): List<DataSourceEntry> {
    val bySlug = sources.associateBy { it.slug }
    val visited = mutableSetOf<String>()
    val visiting = mutableSetOf<String>()
    val out = mutableListOf<DataSourceEntry>()

    fun visit(src: DataSourceEntry) {
        if (src.slug in visited) return
        require(src.slug !in visiting) { "depends_on cycle on ${src.slug}" }
        visiting += src.slug
        for (dep in src.dependsOn) {
            bySlug[dep]?.let { visit(it) }
        }
        visiting -= src.slug
        visited += src.slug
        out += src
    }

    for (src in sources) visit(src)
    return out
}

fun importTargetsFromRegistry(registry: PoiRegistry): Map<String, Target> {
    val log = LoggerFactory.getLogger("RegistryTargets")
    val out = mutableMapOf<String, Target>()
    val implemented = EtlOrchestrator.registry.keys
    val implementedJoiners = emptySet<String>()

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
                    "poi_data '{}' has disabled or unwired etl slugs {} — import will be a no-op until canonical writers land",
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

    // reservable_data — legacy section retained as disabled campsite catalog
    // declarations until canonical campgrounds/campsites writers replace it.
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
                    "reservable_data '{}' has disabled or unwired etl slugs {} — import will be a no-op until canonical writers land",
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

    // poi_reservable_joiner — legacy section retained as disabled campsite
    // parent-resolver declarations. Re-enable only after it writes canonical
    // campsite/campground relationships.
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
                    "poi_reservable_joiner '{}' adapter '{}' is disabled or not registered — run will be a no-op",
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
    return Phase.Fetch(label, cmd, timeoutSec = src.fetcher.timeoutSec)
}
