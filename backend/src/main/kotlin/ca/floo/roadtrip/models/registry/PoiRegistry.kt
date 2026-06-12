package ca.floo.roadtrip.models.registry

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import java.io.File

// In-memory representation of config/poi-registry.yaml.
//
// Four sections:
//   - data_sources: fetchers (executor + filename + args + output_dir_prefix).
//     One entry per upstream feed.
//   - poi_data: POI datasets. Terminal etl emits Poi.* rows into `pois`.
//     Each row carries name, optional enabled (default true), category,
//     optional subcategory, and an ordered etls: list. (Existing flow.)
//   - reservable_data: reservable catalogs (RFC 0008). Terminal etl emits
//     reservable rows into `reservables`. Same chain shape as poi_data,
//     minus category/subcategory (reservables aren't map pins).
//   - poi_reservable_joiner: N:M-link discovery (RFC 0008). Each entry
//     names an adapter that reads the current state of `pois` +
//     `reservables` and writes the (reservable_id, poi_id) link rows
//     into `reservable_pois`. No etl chain — joiners don't transform raw
//     data, they query DB tables.
//
// Etl chain semantics (poi_data + reservable_data):
//   - Terminal stage = last etls entry. Earlier entries are intermediates.
//   - List order = dependency order. Entry N may only reference
//     data_source slugs OR earlier siblings via inputs.
//   - Cross-row etl refs and forward refs are rejected at boot.
//   - Cycles in the global DAG are rejected at boot.
//
// All sections share the slug namespace because `inputs:` resolves to
// either a data_source or an earlier sibling etl. Etl slugs across
// poi_data + reservable_data must not collide; data_source slugs must
// not collide with any etl slug.
//
// Loaded once at boot. Used by:
//   1. EtlOrchestrator — runs etl chains in declared order, dispatching
//      poi_data terminals to Pois Upsert and reservable_data terminals
//      to ReservableRepo. Also runs joiner adapters.
//   2. IngestController / RegistryTargets — fetch is per data_source;
//      import targets cover all three of {poi_data, reservable_data,
//      poi_reservable_joiner}.
//
// Adding a new POI source: one data_sources row + one poi_data row +
// one EtlOrchestrator.registry line per ETL slug. No Flyway migration.
//
// Adding a new reservable source: same shape but reservable_data row.
// Then add a poi_reservable_joiner row pointing at the matching joiner
// adapter so the catalog rows get linked to their parent POIs.
@Serializable
data class PoiRegistry(
    @kotlinx.serialization.SerialName("data_sources")
    val dataSources: List<DataSourceEntry>,
    @kotlinx.serialization.SerialName("poi_data")
    val poiData: List<PoiDataEntry>,
    @kotlinx.serialization.SerialName("reservable_data")
    val reservableData: List<ReservableDataEntry> = emptyList(),
    @kotlinx.serialization.SerialName("poi_reservable_joiner")
    val poiReservableJoiners: List<PoiReservableJoinerEntry> = emptyList(),
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

        // Etl slugs share a namespace with data_source slugs because
        // inputs: resolves to either. Detect collisions across both
        // poi_data and reservable_data — an etl in either section can be
        // an input to an etl in the same section's row, but not across
        // sections (the orchestrator hands intermediates over in memory
        // within one runPoiData / runReservableData invocation, not
        // between them).
        val etlSlugs = mutableSetOf<String>()
        validateEtlSection(
            label = "poi_data",
            rows = poiData.map { EtlRowRef(it.name, it.etls, it) },
            dsSlugs = dsSlugs,
            allEtlSlugs = etlSlugs,
            errs = errs,
        )
        validateEtlSection(
            label = "reservable_data",
            rows = reservableData.map { EtlRowRef(it.name, it.etls, it) },
            dsSlugs = dsSlugs,
            allEtlSlugs = etlSlugs,
            errs = errs,
        )

        // Joiner-row sanity: name uniqueness + non-empty adapter. Joiners
        // don't have etl chains so there's no input/forward-ref check.
        val joinerNames = mutableSetOf<String>()
        for ((i, j) in poiReservableJoiners.withIndex()) {
            if (!joinerNames.add(j.name)) {
                errs += "poi_reservable_joiner[$i] name='${j.name}' is not unique"
            }
            if (j.adapter.isBlank()) {
                errs += "poi_reservable_joiner '${j.name}' has empty adapter"
            }
        }

        // Global cycle detection over data_sources.depends_on + every
        // etl.inputs across both etl-bearing sections. Edges run
        // input → consumer.
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
            for (row in reservableData) {
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

    /**
     * Per-section etl validation. Walks one section's rows and applies
     * the universal constraints: non-empty etl chain, slug uniqueness
     * across all etl-bearing sections, no collision with data_source
     * slugs, no forward references, no cross-row references within the
     * section.
     *
     * Cross-section refs are caught here too: if an input slug points
     * at an etl in a different section (or a different row in this
     * section), it gets the "neither a data_source nor a prior sibling"
     * error. The orchestrator hands intermediates in-memory within one
     * runPoiData/runReservableData call, so cross-row state never exists.
     */
    private fun validateEtlSection(
        label: String,
        rows: List<EtlRowRef>,
        dsSlugs: Set<String>,
        allEtlSlugs: MutableSet<String>,
        errs: MutableList<String>,
    ) {
        for (row in rows) {
            if (row.etls.isEmpty()) {
                errs += "$label '${row.name}' has empty etls list"
                continue
            }
            for ((i, e) in row.etls.withIndex()) {
                if (e.slug in dsSlugs) {
                    errs += "$label '${row.name}' etl[$i] slug='${e.slug}' collides with a data_source slug"
                }
                if (!allEtlSlugs.add(e.slug)) {
                    errs += "duplicate etl slug='${e.slug}' (in $label '${row.name}')"
                }
            }
            val priorSiblings = mutableSetOf<String>()
            for ((i, e) in row.etls.withIndex()) {
                for (input in e.inputs) {
                    val resolvesToDataSource = input in dsSlugs
                    val resolvesToPriorSibling = input in priorSiblings
                    if (!resolvesToDataSource && !resolvesToPriorSibling) {
                        val laterSibling =
                            row.etls.drop(i + 1).any { it.slug == input }
                        val crossRowSameSection =
                            !laterSibling &&
                                rows.any { other ->
                                    other.source !== row.source && other.etls.any { it.slug == input }
                                }
                        val crossSection =
                            !laterSibling &&
                                !crossRowSameSection &&
                                otherSectionsHaveEtlSlug(label, input)
                        errs +=
                            when {
                                laterSibling ->
                                    "$label '${row.name}' etl[$i] '${e.slug}' inputs '$input' which is a later sibling (forward reference)"
                                crossRowSameSection ->
                                    "$label '${row.name}' etl[$i] '${e.slug}' inputs '$input' which is an etl in a different $label row (cross-row refs not supported)"
                                crossSection ->
                                    "$label '${row.name}' etl[$i] '${e.slug}' inputs '$input' which is an etl in a different section (cross-section refs not supported)"
                                else ->
                                    "$label '${row.name}' etl[$i] '${e.slug}' inputs '$input' which is neither a data_source nor a prior sibling"
                            }
                    }
                }
                priorSiblings += e.slug
            }
        }
    }

    /** True iff some section *other than* [exclude] declares an etl with [slug]. */
    private fun otherSectionsHaveEtlSlug(
        exclude: String,
        slug: String,
    ): Boolean {
        if (exclude != "poi_data" && poiData.any { row -> row.etls.any { it.slug == slug } }) return true
        if (exclude != "reservable_data" && reservableData.any { row -> row.etls.any { it.slug == slug } }) return true
        return false
    }

    /** Section-agnostic row pointer used by [validateEtlSection]. */
    private data class EtlRowRef(
        val name: String,
        val etls: List<EtlEntry>,
        /** Original entry; used as identity for the cross-row check. */
        val source: Any,
    )

    /** poi_data rows that should run during fan-out import. */
    fun enabledPoiData(): List<PoiDataEntry> = poiData.filter { it.enabled }

    /**
     * Look up the data_source entry that backs a fetch target. Returns null
     * for unknown slugs (caller should 404).
     */
    fun dataSource(slug: String): DataSourceEntry? = dataSources.firstOrNull { it.slug == slug }

    /** Look up a poi_data row by its display name. Names are unique by convention. */
    fun poiDataByName(name: String): PoiDataEntry? = poiData.firstOrNull { it.name == name }

    /** reservable_data rows that should run during fan-out import. */
    fun enabledReservableData(): List<ReservableDataEntry> = reservableData.filter { it.enabled }

    /** Look up a reservable_data row by its display name. */
    fun reservableDataByName(name: String): ReservableDataEntry? = reservableData.firstOrNull { it.name == name }

    /** poi_reservable_joiner rows that should run during fan-out import. */
    fun enabledPoiReservableJoiners(): List<PoiReservableJoinerEntry> = poiReservableJoiners.filter { it.enabled }

    /** Look up a poi_reservable_joiner row by its display name. */
    fun poiReservableJoinerByName(name: String): PoiReservableJoinerEntry? = poiReservableJoiners.firstOrNull { it.name == name }

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
     *
     * Used by [ca.floo.roadtrip.service.booking.BookingProviderRegistry]
     * to construct one [ca.floo.roadtrip.service.booking.adapters.aspira.AspiraBookingProvider]
     * instance per host (Parks Canada / BC / WA). Routes never see this map
     * directly — they go through the booking-provider registry.
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

    /**
     * Sources whose terminal ETL produces rec.gov-keyed campgrounds. Used
     * by the booking-provider registry to map `pois.source` → `RECGOV`.
     */
    fun recgovSources(): Set<String> =
        poiData
            .mapNotNull { row -> row.etls.lastOrNull() }
            .filter { it.adapter == "RecGovCampgroundsEtl" }
            .map { it.slug }
            .toSet()

    /**
     * Sources whose terminal ETL produces Camis-keyed campgrounds (Alberta
     * Provincial via ReserveAmericaEtl today; future Camis-direct ETLs
     * would join here too).
     */
    fun camisSources(): Set<String> =
        poiData
            .mapNotNull { row -> row.etls.lastOrNull() }
            .filter { it.adapter == "ReserveAmericaEtl" }
            .map { it.slug }
            .toSet()
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

/**
 * Row in the `reservable_data` section. Same shape as [PoiDataEntry] minus
 * `category` / `subcategory` — reservables aren't map pins, so the FE
 * legend metadata doesn't apply. The terminal etl emits reservable rows
 * via [ca.floo.roadtrip.repo.ReservableRepo]; the orchestrator dispatches
 * by section, not by etl marker interface.
 */
@Serializable
data class ReservableDataEntry(
    val name: String,
    val enabled: Boolean = true,
    val etls: List<EtlEntry>,
)

/**
 * Row in the `poi_reservable_joiner` section. Names a single adapter
 * (registered in EtlOrchestrator's joiner registry) that reads the
 * current state of `pois` + `reservables` and writes (reservable_id,
 * poi_id) link rows into `reservable_pois`. No etl chain; joiners
 * don't transform raw data, they query DB tables.
 *
 * `args` follows the same shape as [EtlEntry.args]: free-form
 * adapter-specific config (e.g. which provider source to scope to).
 */
@Serializable
data class PoiReservableJoinerEntry(
    val name: String,
    val enabled: Boolean = true,
    val adapter: String,
    val args: Map<String, String> = emptyMap(),
)
