package ca.floo.roadtrip.model.metadata.registry

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.charset.StandardCharsets

private const val CAMPSITE_DATA_SECTION = "campsite_data"

// In-memory representation of the configured POI registry.
//
// Four sections:
//   - data_sources: fetchers (executor + filename + args + output_dir_prefix).
//     One entry per upstream feed.
//   - poi_data: POI datasets. Terminal etl emits catalog upsert candidates.
//     Each row carries name, optional enabled (default true), category,
//     optional subcategory, and exactly one etls entry.
//   - campsite_data: campsite catalogs. Terminal etl emits canonical campsite
//     rows. Same shape as poi_data, minus category/subcategory
//     (campsites aren't map pins).
// ETL semantics (poi_data + campsite_data):
//   - Each row has exactly one terminal ETL.
//   - ETL inputs may only reference data_source slugs.
//   - Cycles in the global DAG are rejected at boot.
//
// All sections share the slug namespace. Etl slugs across poi_data +
// campsite_data must not collide; data_source slugs must not collide with
// any etl slug.
//
// Loaded once at boot. Used by:
//   1. EtlOrchestrator — runs etl chains in declared order, dispatching
//      poi_data terminals to Pois Upsert and campsite_data terminals
//      to CampsiteRepo.
//   2. scripts/poll_raw.py — fetch is per data_source and runs outside
//      the backend process.
//   3. IngestController / RegistryTargets — import targets cover
//      poi_data and campsite_data.
//
// Adding a new POI source: one data_sources row + one poi_data row +
// one EtlOrchestrator.registry line per ETL slug. No Flyway migration.
//
// Adding a new campsite source: same shape but campsite_data row.
@Serializable
class PoiRegistry(
    @kotlinx.serialization.SerialName("data_sources")
    val dataSources: List<DataSourceEntry>,
    @kotlinx.serialization.SerialName("poi_data")
    val poiData: List<PoiDataEntry>,
    @kotlinx.serialization.SerialName("campsite_data")
    val campsiteData: List<CampsiteDataEntry> = emptyList(),
) {
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
     *   - poi_data.etls/campsite_data.etls has exactly one terminal entry
     *   - every etl input is a data_source slug
     *   - no cycles in the global DAG (data_sources → etls)
     */
    fun validate(sourceName: String = "POI registry") {
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

        // Etl slugs share a namespace with data_source slugs. Detect
        // collisions across both poi_data and campsite_data.
        val etlSlugs = mutableSetOf<String>()
        for (row in poiData) {
            try {
                row.agency
            } catch (e: IllegalArgumentException) {
                errs += "poi_data '${row.name}' has invalid agency: ${e.message}"
            }
        }
        validateEtlSection(
            label = "poi_data",
            rows = poiData.map { EtlRowRef(it.name, it.etls) },
            dsSlugs = dsSlugs,
            allEtlSlugs = etlSlugs,
            errs = errs,
        )
        validateEtlSection(
            label = CAMPSITE_DATA_SECTION,
            rows = campsiteData.map { EtlRowRef(it.name, it.etls) },
            dsSlugs = dsSlugs,
            allEtlSlugs = etlSlugs,
            errs = errs,
        )

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
            for (row in campsiteData) {
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
            "$sourceName has ${errs.size} validation error(s):\n" +
                errs.joinToString("\n") { "  - $it" }
        }
    }

    /**
     * Per-section etl validation. Walks one section's rows and applies the
     * universal constraints: exactly one etl, slug uniqueness across all
     * etl-bearing sections, no collision with data_source slugs, and inputs
     * that resolve directly to data_source slugs.
     */
    private fun validateEtlSection(
        label: String,
        rows: List<EtlRowRef>,
        dsSlugs: Set<String>,
        allEtlSlugs: MutableSet<String>,
        errs: MutableList<String>,
    ) {
        for (row in rows) {
            if (row.etls.size != 1) {
                errs += "$label '${row.name}' must declare exactly one etl (got ${row.etls.size})"
            }
            for ((i, e) in row.etls.withIndex()) {
                if (e.slug in dsSlugs) {
                    errs += "$label '${row.name}' etl[$i] slug='${e.slug}' collides with a data_source slug"
                }
                if (!allEtlSlugs.add(e.slug)) {
                    errs += "duplicate etl slug='${e.slug}' (in $label '${row.name}')"
                }
                for (input in e.inputs) {
                    if (input !in dsSlugs) {
                        errs += "$label '${row.name}' etl[$i] '${e.slug}' inputs '$input' which is not a data_source"
                    }
                }
            }
        }
    }

    /** Section-agnostic row pointer used by [validateEtlSection]. */
    private data class EtlRowRef(
        val name: String,
        val etls: List<EtlEntry>,
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

    /** campsite_data rows that should run during fan-out import. */
    fun enabledCampsiteData(): List<CampsiteDataEntry> = campsiteData.filter { it.enabled }

    /** Look up a campsite_data row by its display name. */
    fun campsiteDataByName(name: String): CampsiteDataEntry? = campsiteData.firstOrNull { it.name == name }

    /**
     * Static subcategory lookup keyed by terminal etl slug.
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

    fun agencyByTerminalEtlSlug(): Map<String, AgencyConfig?> {
        val out = mutableMapOf<String, AgencyConfig?>()
        for (row in poiData) {
            val terminal = row.etls.lastOrNull() ?: continue
            out[terminal.slug] = row.agency
        }
        return out
    }

    /**
     * Aspira upstream host keyed by terminal etl slug.
     * Returns the `host` arg from the terminal AspiraCampgroundsEtl row.
     *
     * Used by [ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry]
     * to construct one [ca.floo.roadtrip.service.availability.provider.AspiraAvailabilityProvider]
     * instance per host (Parks Canada / BC / WA). Routes never see this map
     * directly — they go through the availability-provider registry.
     */
    fun hostBySource(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (row in poiData) {
            val terminal = row.etls.lastOrNull() ?: continue
            val host = terminal.args["host"] ?: continue
            out[terminal.slug] = host
        }
        return out
    }

    /**
     * Sources whose terminal ETL produces rec.gov-keyed campgrounds. Used
     * by the availability-provider registry to map the terminal etl slug → `RECGOV`.
     */
    fun recgovSources(): Set<String> =
        poiData
            .mapNotNull { row -> row.etls.lastOrNull() }
            .filter { it.adapter == "RecGovCampgroundsEtl" }
            .map { it.slug }
            .toSet()

    fun campflareSources(): Set<String> =
        poiData
            .mapNotNull { row -> row.etls.lastOrNull() }
            .filter { it.adapter == "CampflareCampgroundsEtl" }
            .map { it.slug }
            .toSet()

    fun bcParksSources(): Set<String> =
        poiData
            .mapNotNull { row -> row.etls.lastOrNull() }
            .filter { it.adapter == "BcParksCampgroundsEtl" }
            .map { it.slug }
            .toSet()

    /**
     * ReserveAmerica terminal ETL sources with their Active Network tenant
     * config. Unlike Aspira, these tenants are fully config-driven because the
     * contract code, host, and booking horizon are all declared on the
     * terminal ETL row.
     */
    fun reserveAmericaSources(): List<ReserveAmericaSourceConfig> =
        poiData
            .mapNotNull { row -> row.etls.lastOrNull() }
            .filter { it.adapter == "ReserveAmericaCampgroundsEtl" }
            .filter { (it.args["provider"] ?: "reserveamerica").lowercase() == "reserveamerica" }
            .map { terminal ->
                val contract =
                    terminal.args["contract"]
                        ?: error("ReserveAmerica source '${terminal.slug}' is missing args.contract")
                val host =
                    terminal.args["host"]
                        ?: error("ReserveAmerica source '${terminal.slug}' is missing args.host")
                val horizon =
                    terminal.args["booking_horizon_days"]
                        ?.toIntOrNull()
                        ?: error("ReserveAmerica source '${terminal.slug}' has invalid args.booking_horizon_days")
                require(horizon > 0) {
                    "ReserveAmerica source '${terminal.slug}' args.booking_horizon_days must be positive"
                }
                ReserveAmericaSourceConfig(
                    source = terminal.slug,
                    host = host,
                    contractCode = contract,
                    bookingHorizonDays = horizon,
                )
            }

    fun reserveCaliforniaSources(): Set<String> =
        poiData
            .mapNotNull { row -> row.etls.lastOrNull() }
            .filter { it.adapter == "ReserveCaliforniaCampgroundsEtl" }
            .map { it.slug }
            .toSet()

    companion object {
        private val yaml =
            Yaml(
                configuration =
                    com.charleskorn.kaml.YamlConfiguration(strictMode = false),
            )

        fun load(file: File): PoiRegistry =
            loadString(
                content = file.readText(),
                sourceName = file.path,
            )

        fun loadResource(
            resourceName: String,
            classLoader: ClassLoader = Thread.currentThread().contextClassLoader ?: PoiRegistry::class.java.classLoader,
        ): PoiRegistry {
            val normalized = resourceName.trim().removePrefix("/")
            require(normalized.isNotEmpty()) { "POI registry resource name must not be blank" }
            val content =
                classLoader
                    .getResourceAsStream(normalized)
                    ?.bufferedReader(StandardCharsets.UTF_8)
                    ?.use { it.readText() }
                    ?: error("POI registry resource '$normalized' not found on classpath")
            return loadString(content = content, sourceName = "classpath:$normalized")
        }

        fun loadString(
            content: String,
            sourceName: String = "POI registry",
        ): PoiRegistry {
            val r = yaml.decodeFromString(serializer(), content)
            r.validate(sourceName)
            return r
        }
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

internal fun YamlNode.toAgencyConfig(): AgencyConfig =
    when (this) {
        is YamlScalar -> {
            val value = content.takeIf { it.isNotBlank() }
            require(value != null) { "agency must not be blank" }
            AgencyConfig.Constant(value)
        }
        is YamlMap -> {
            val key = AgencyConfig.DERIVED_FROM_FIELD_KEY
            val entries = entries.mapKeys { (k, _) -> k.content }
            val unknown = entries.keys - key
            require(unknown.isEmpty()) {
                "supports only '$key'; unknown keys: ${unknown.joinToString()}"
            }
            val derived = entries[key] as? YamlScalar
            require(derived != null) { "$key must be a scalar" }
            val field = derived.content.takeIf { it.isNotBlank() }
            require(field != null) { "$key must not be blank" }
            AgencyConfig.DerivedFromField(field)
        }
        else -> throw IllegalArgumentException("agency must be a scalar string or mapping")
    }
