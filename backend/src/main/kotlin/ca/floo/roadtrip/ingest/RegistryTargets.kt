package ca.floo.roadtrip.ingest

import ca.floo.roadtrip.etl.registry.PoiRegistry
import ca.floo.roadtrip.etl.registry.SourceEntry
import java.io.File

// Derives the IngestController target map from config/poi-registry.yaml
// (RFC 0007 PR 3.5). The map carries two flavors:
//
//   1. Per-source targets — one Target per `source.id`. Lets the
//      operator refresh just one upstream (e.g.
//      `make poll TARGET=aspira-maps-bc`).
//
//   2. Per-governing-body targets — one Target per `governing_body.slug`
//      that has any sources. Aggregates every source under that body.
//      This is what `make refresh GOVERNING=alberta-parks` lands in.
//
// Adding a new vendor: append a `sources:` row in YAML + register the ETL
// in EtlOrchestrator.registry. No edits to this file. The Python script
// it points at can reuse an existing fetcher (most do — `fetch_aspira_maps`
// works for any Aspira host with --host=); a new vendor only needs a new
// fetcher when its protocol/auth shape is novel.
fun targetsFromRegistry(
    registry: PoiRegistry,
    repoRoot: File,
): Map<String, Target> {
    val out = mutableMapOf<String, Target>()

    // 1. Per-source targets.
    for (gb in registry.governingBodies) {
        for (src in gb.sources) {
            out[src.id] =
                Target(
                    name = src.id,
                    fetchPhases = listOf(fetchPhaseFor(src, repoRoot)),
                    importPhases = listOf(Phase.Import("import:${src.id}", src.id)),
                )
        }
    }

    // 2. Per-governing-body aggregate targets — only for bodies that
    //    actually have sources. Marked aggregate=true so the no-target
    //    fan-out skips them; explicit calls (TARGET=alberta-parks) still
    //    work.
    for (gb in registry.governingBodies) {
        if (gb.sources.isEmpty()) continue
        // depends_on order: a source can declare it depends on other sources
        // (Tesla locations needs the index first). Topo-sort within the body.
        val ordered = topoSort(gb.sources)
        // Skip if the body has only one source — the per-source target
        // already does the same thing.
        if (ordered.size == 1) continue
        out[gb.slug] =
            Target(
                name = gb.slug,
                fetchPhases = ordered.map { fetchPhaseFor(it, repoRoot) },
                importPhases = ordered.map { Phase.Import("import:${it.id}", it.id) },
                aggregate = true,
            )
    }

    return out
}

private fun fetchPhaseFor(
    src: SourceEntry,
    repoRoot: File,
): Phase.Fetch {
    val python = listOf("python3")
    val script = repoRoot.resolve("scripts/${src.fetcher}.py").absolutePath
    val cliArgs = src.args.flatMap { (k, v) -> listOf("--$k", v) }
    return Phase.Fetch("${src.fetcher}.py" + (if (cliArgs.isEmpty()) "" else " ${src.id}"), python + script + cliArgs)
}

// Stable topological sort respecting depends_on. Cycles error out.
private fun topoSort(sources: List<SourceEntry>): List<SourceEntry> {
    val byId = sources.associateBy { it.id }
    val visited = mutableSetOf<String>()
    val visiting = mutableSetOf<String>()
    val out = mutableListOf<SourceEntry>()

    fun visit(s: SourceEntry) {
        if (s.id in visited) return
        check(s.id !in visiting) { "depends_on cycle on source id=${s.id}" }
        visiting += s.id
        for (dep in s.dependsOn) {
            val depSrc =
                byId[dep]
                    ?: error("source id=${s.id} depends_on='$dep' which is not declared in this governing body")
            visit(depSrc)
        }
        visiting -= s.id
        visited += s.id
        out += s
    }
    for (s in sources) visit(s)
    return out
}
