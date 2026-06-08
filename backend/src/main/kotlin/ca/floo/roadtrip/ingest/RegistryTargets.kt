package ca.floo.roadtrip.ingest

import ca.floo.roadtrip.etl.EtlOrchestrator
import ca.floo.roadtrip.etl.registry.DataSourceEntry
import ca.floo.roadtrip.etl.registry.PoiRegistry
import org.slf4j.LoggerFactory
import java.io.File

// Derives the IngestController target map from config/poi-registry.yaml.
// One Target per enabled data_source. Lets the operator refresh just one
// upstream (e.g. `make poll TARGET=osm-pf`).
//
// Adding a new vendor: append a `data_sources:` row in YAML + register
// the ETL in EtlOrchestrator.registry. No edits to this file. If a
// data_source declares an etl_adapter that isn't yet wired into
// EtlOrchestrator.registry, the target gets a fetch phase but no import
// phase — the adapter slot can be reserved in YAML before the Kotlin
// class lands, and the import becomes a noop instead of failing.
fun targetsFromRegistry(
    registry: PoiRegistry,
    repoRoot: File,
): Map<String, Target> {
    val log = LoggerFactory.getLogger("RegistryTargets")
    val out = mutableMapOf<String, Target>()
    val implemented = EtlOrchestrator.registry.keys

    for (src in registry.enabledSources()) {
        val importPhases =
            if (src.slug in implemented) {
                listOf(Phase.Import("import:${src.slug}", src.slug))
            } else {
                if (src.etlAdapter != null) {
                    log.warn(
                        "data_source slug={} declares etl_adapter={} which isn't in " +
                            "EtlOrchestrator.registry — fan-out will fetch but skip import",
                        src.slug,
                        src.etlAdapter,
                    )
                }
                emptyList()
            }
        // fetcher.enabled=false sources keep their import phase but skip
        // the fetch step. Use case: the upstream is unreachable (Tesla's
        // Akamai blocks us, cookies stale) and we want to keep importing
        // from the existing raw cache without re-fetching.
        val fetchPhases =
            if (src.fetcher.enabled) {
                listOf(fetchPhaseFor(src, repoRoot))
            } else {
                log.info(
                    "data_source slug={} has fetcher.enabled=false — fan-out will skip fetch",
                    src.slug,
                )
                emptyList()
            }
        out[src.slug] =
            Target(
                name = src.slug,
                fetchPhases = fetchPhases,
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
