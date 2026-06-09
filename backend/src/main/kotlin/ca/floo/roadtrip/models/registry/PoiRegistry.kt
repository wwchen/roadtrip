package ca.floo.roadtrip.models.registry

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import java.io.File

// In-memory representation of config/poi-registry.yaml.
//
// Two sections:
//   - data_sources: fetchers (executor + filename + args + output_dir_prefix).
//     One entry per upstream feed.
//   - poi_data: user-facing datasets. Each row carries name, optional enabled
//     (default true), category, optional subcategory, and an ordered etls:
//     list. The LAST etls entry is the POI emitter (must produce Poi.*);
//     earlier entries are intermediates. List order is dependency order:
//     entry N may only reference data_source slugs OR earlier siblings via
//     inputs. The framework topo-sorts the global DAG; cycles + forward
//     references are rejected at boot.
//
// Loaded once at boot. Used by:
//   1. EtlOrchestrator — runs each poi_data row's etls chain in order,
//      materializing intermediates to data/etl-out/<etl-slug>/ and upserting
//      the terminal's Poi.* output to the pois table with source=<terminal-slug>.
//   2. IngestController / RegistryTargets — fetch is per data_source,
//      import is per poi_data row.
//
// Adding a new source = one data_sources row + one poi_data row + one
// EtlOrchestrator.registry line per ETL slug. No Flyway migration.
@Serializable
data class PoiRegistry(
    @kotlinx.serialization.SerialName("data_sources")
    val dataSources: List<DataSourceEntry>,
    @kotlinx.serialization.SerialName("poi_data")
    val poiData: List<PoiDataEntry>,
) {
    companion object {
        private val yaml =
            Yaml(
                configuration =
                    com.charleskorn.kaml.YamlConfiguration(strictMode = false),
            )

        fun load(file: File): PoiRegistry {
            val r = yaml.decodeFromString(serializer(), file.readText())
            r.validate()
            return r
        }
    }

    /**
     * Sanity-check the registry after deserialization. Catches typos /
     * dangling references / cycles at boot rather than at first row-insert.
     *
     * Checks:
     *   - data_source slugs unique
     *   - etl slugs unique across the whole YAML, AND distinct from any
     *     data_source slug (single namespace because inputs: resolves to
     *     either kind)
     *   - data_sources.depends_on references a declared data_source
     *   - poi_data.etls is non-empty (terminal is the last entry)
     *   - within each row's etls, every input is either a data_source slug
     *     OR an earlier sibling in the SAME row (forward refs + cross-row
     *     etl refs both rejected — orchestrator hands intermediates over
     *     in memory; nothing crosses row boundaries)
     *   - no cycles in the global DAG (data_sources → etls)
     */
    fun validate() {
        val errs = mutableListOf<String>()

        val dsSlugs = mutableSetOf<String>()
        for (ds in dataSources) {
            if (!dsSlugs.add(ds.slug)) errs += "duplicate data_source slug='${ds.slug}'"
        }
        for (ds in dataSources) {
            for (dep in ds.dependsOn) {
                if (dep !in dsSlugs) errs += "data_source '${ds.slug}'.depends_on='$dep' is not a declared slug"
            }
        }

        // ETL slugs share a namespace with data_source slugs because
        // inputs: resolves to either. Detect collisions.
        val etlSlugs = mutableSetOf<String>()
        for (row in poiData) {
            if (row.etls.isEmpty()) {
                errs += "poi_data '${row.name}' has empty etls list"
                continue
            }
            for ((i, e) in row.etls.withIndex()) {
                if (e.slug in dsSlugs) {
                    errs += "poi_data '${row.name}' etl[$i] slug='${e.slug}' collides with a data_source slug"
                }
                if (!etlSlugs.add(e.slug)) {
                    errs += "duplicate etl slug='${e.slug}' (in poi_data '${row.name}')"
                }
            }
            // Per-row forward-reference check. Cross-row etl refs fall in
            // the same bucket: orchestrator hands intermediates over in
            // memory, so an input has to be either a data_source or a
            // prior sibling in this row's chain.
            val priorSiblings = mutableSetOf<String>()
            for ((i, e) in row.etls.withIndex()) {
                for (input in e.inputs) {
                    val resolvesToDataSource = input in dsSlugs
                    val resolvesToPriorSibling = input in priorSiblings
                    if (!resolvesToDataSource && !resolvesToPriorSibling) {
                        val laterSibling =
                            row.etls.drop(i + 1).any { it.slug == input }
                        val crossRow =
                            !laterSibling &&
                                poiData.any { other ->
                                    other !== row && other.etls.any { it.slug == input }
                                }
                        errs +=
                            when {
                                laterSibling ->
                                    "poi_data '${row.name}' etl[$i] '${e.slug}' inputs '$input' which is a later sibling (forward reference)"
                                crossRow ->
                                    "poi_data '${row.name}' etl[$i] '${e.slug}' inputs '$input' which is an etl in a different poi_data row (cross-row refs not supported)"
                                else ->
                                    "poi_data '${row.name}' etl[$i] '${e.slug}' inputs '$input' which is neither a data_source nor a prior sibling"
                            }
                    }
                }
                priorSiblings += e.slug
            }
        }

        // Global cycle detection over data_sources.depends_on + every
        // etl.inputs. Edges run input → consumer. The (data_sources +
        // etls) namespace is one set; a cycle means one of the .inputs
        // slugs eventually depends on the consumer.
        if (errs.isEmpty()) {
            val edges = mutableMapOf<String, MutableSet<String>>()

            fun edge(
                from: String,
                to: String,
            ) {
                edges.getOrPut(from) { mutableSetOf() }.add(to)
            }
            for (ds in dataSources) {
                for (dep in ds.dependsOn) edge(dep, ds.slug)
            }
            for (row in poiData) {
                for (e in row.etls) {
                    for (input in e.inputs) edge(input, e.slug)
                }
            }
            val cycles = detectCycles(edges)
            if (cycles.isNotEmpty()) {
                for (cycle in cycles) {
                    errs += "cycle in DAG: ${cycle.joinToString(" → ")}"
                }
            }
        }

        require(errs.isEmpty()) {
            "config/poi-registry.yaml has ${errs.size} validation error(s):\n" +
                errs.joinToString("\n") { "  - $it" }
        }
    }

    /** poi_data rows that should run during fan-out import. */
    fun enabledPoiData(): List<PoiDataEntry> = poiData.filter { it.enabled }

    /**
     * Look up the data_source entry that backs a fetch target. Returns null
     * for unknown slugs (caller should 404).
     */
    fun dataSource(slug: String): DataSourceEntry? = dataSources.firstOrNull { it.slug == slug }

    /** Look up a poi_data row by its display name. Names are unique by convention. */
    fun poiDataByName(name: String): PoiDataEntry? = poiData.firstOrNull { it.name == name }

    /**
     * Static subcategory lookup keyed by terminal etl slug (== pois.source).
     * Returns null when the row has no subcategory (e.g. planet-fitness).
     */
    fun subcategoryByTerminalEtlSlug(): Map<String, String?> {
        val out = mutableMapOf<String, String?>()
        for (row in poiData) {
            val terminal = row.etls.lastOrNull() ?: continue
            out[terminal.slug] = row.subcategory
        }
        return out
    }

    /**
     * Aspira upstream host keyed by terminal etl slug (== pois.source).
     * Returns the `host` arg from the terminal AspiraJoinByNameEtl row.
     * Used by the unified availability endpoint to dispatch aspira-backed
     * pois to the right reservation host (Parks Canada / BC / WA) without
     * the FE having to know the mapping.
     */
    fun aspiraHostBySource(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (row in poiData) {
            val terminal = row.etls.lastOrNull() ?: continue
            if (!terminal.adapter.startsWith("Aspira")) continue
            val host = terminal.args["host"] ?: continue
            out[terminal.slug] = host
        }
        return out
    }
}

private fun detectCycles(edges: Map<String, Set<String>>): List<List<String>> {
    val visited = mutableSetOf<String>()
    val onStack = mutableSetOf<String>()
    val stack = ArrayDeque<String>()
    val cycles = mutableListOf<List<String>>()

    fun dfs(node: String) {
        visited.add(node)
        onStack.add(node)
        stack.addLast(node)
        for (next in edges[node].orEmpty()) {
            if (next !in visited) {
                dfs(next)
            } else if (next in onStack) {
                val from = stack.indexOf(next)
                if (from >= 0) {
                    val cyc = stack.toList().subList(from, stack.size) + next
                    cycles.add(cyc)
                }
            }
        }
        onStack.remove(node)
        stack.removeLast()
    }
    for (node in edges.keys) {
        if (node !in visited) dfs(node)
    }
    return cycles
}

@Serializable
data class DataSourceEntry(
    val slug: String,
    val name: String,
    val fetcher: Fetcher,
    @kotlinx.serialization.SerialName("depends_on")
    val dependsOn: List<String> = emptyList(),
)

@Serializable
data class Fetcher(
    val executor: String,
    val filename: String,
    val args: Map<String, String> = emptyMap(),
    @kotlinx.serialization.SerialName("output_dir_prefix")
    val outputDirPrefix: String,
)

@Serializable
data class PoiDataEntry(
    val name: String,
    val enabled: Boolean = true,
    val category: String,
    // FE sub-bucket for legend toggles + circle-color (e.g. campground →
    // federal | state | local | provincial). Null when the category has
    // no sub-bucket (planet-fitness, supercharger).
    val subcategory: String? = null,
    val etls: List<EtlEntry>,
)

@Serializable
data class EtlEntry(
    val slug: String,
    val adapter: String,
    val inputs: List<String> = emptyList(),
    val args: Map<String, String> = emptyMap(),
)
