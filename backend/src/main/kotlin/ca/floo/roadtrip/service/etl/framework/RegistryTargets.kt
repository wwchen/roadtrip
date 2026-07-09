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
// Adding a new runnable import: append an enabled registry row and register
// the ETL adapter(s) in EtlOrchestrator.registry. If a row is disabled or its
// ETL slugs are absent from that registry, no import target is created. This
// keeps legacy vendor rows declared in YAML while omitting them from import
// fan-out and admin status until they write through the canonical catalog.
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
        if (!row.enabled) {
            log.info("poi_data '{}' is disabled - omitting import target", row.name)
            continue
        }
        val unwiredSlugs = row.etls.map { it.slug }.filterNot { it in implemented }
        if (unwiredSlugs.isNotEmpty()) {
            log.warn(
                "poi_data '{}' has disabled or unwired etl slugs {} - omitting import target until canonical writers land",
                row.name,
                unwiredSlugs,
            )
            continue
        }
        out[row.name] =
            Target(
                name = row.name,
                fetchPhases = emptyList(),
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

    // reservable_data — legacy section retained as disabled campsite catalog
    // declarations until canonical campgrounds/campsites writers replace it.
    for (row in registry.reservableData) {
        if (!row.enabled) {
            log.info("reservable_data '{}' is disabled - omitting import target", row.name)
            continue
        }
        val unwiredSlugs = row.etls.map { it.slug }.filterNot { it in implemented }
        if (unwiredSlugs.isNotEmpty()) {
            log.warn(
                "reservable_data '{}' has disabled or unwired etl slugs {} - omitting import target until canonical writers land",
                row.name,
                unwiredSlugs,
            )
            continue
        }
        out[row.name] =
            Target(
                name = row.name,
                fetchPhases = emptyList(),
                importPhases =
                    listOf(
                        Phase.Import(
                            label = "import:${row.name}",
                            name = row.name,
                            section = Phase.Import.Section.RESERVABLE_DATA,
                        ),
                    ),
            )
    }

    // poi_reservable_joiner — legacy section retained as disabled campsite
    // parent-resolver declarations. Re-enable only after it writes canonical
    // campsite/campground relationships.
    for (row in registry.poiReservableJoiners) {
        if (!row.enabled) {
            log.info("poi_reservable_joiner '{}' is disabled - omitting import target", row.name)
            continue
        }
        if (row.adapter !in implementedJoiners) {
            log.warn(
                "poi_reservable_joiner '{}' adapter '{}' is disabled or not registered - omitting import target",
                row.name,
                row.adapter,
            )
            continue
        }
        out[row.name] =
            Target(
                name = row.name,
                fetchPhases = emptyList(),
                importPhases =
                    listOf(
                        Phase.Import(
                            label = "import:${row.name}",
                            name = row.name,
                            section = Phase.Import.Section.POI_RESERVABLE_JOINER,
                        ),
                    ),
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
