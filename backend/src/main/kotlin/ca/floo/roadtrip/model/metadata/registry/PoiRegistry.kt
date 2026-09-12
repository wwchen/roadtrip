package ca.floo.roadtrip.model.metadata.registry

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.charset.StandardCharsets

private const val CAMPSITE_DATA_SECTION = "campsite_data"
private const val POI_DATA_SECTION = "poi_data"

/**
 * The one place an ETL arg key is tied to a vendor. Everything else names the
 * vendor and derives the key from here, so the two facts cannot drift.
 */
@Suppress("TopLevelPropertyNaming")
private val TENANT_ARG_KEYS =
    mapOf(
        BookingProvider.ASPIRA to ARG_TENANT,
        BookingProvider.RESERVEAMERICA to "contract",
    )

private const val ARG_HOST = "host"
private const val ARG_TENANT = "tenant"
private const val ARG_MAPS_INPUT = "maps_input"
private const val ARG_INVENTORY_INPUT = "inventory_input"
private const val ARG_DICTIONARIES_INPUT = "dictionaries_input"
private const val ARG_PARENT_DATA_PROVIDER = "parent_data_provider"

private const val ASPIRA_CAMPGROUNDS_ADAPTER = "AspiraCampgroundsEtl"
private const val BC_PARKS_CAMPGROUNDS_ADAPTER = "BcParksCampgroundsEtl"

private const val MIN_FUZZY_THRESHOLD_EXCLUSIVE = 0.0
private const val MAX_FUZZY_THRESHOLD_INCLUSIVE = 1.0

private const val SOURCE_STATE_KEY = "state"
private const val SOURCE_NAME_PROPERTY_KEY = "name_property"

/**
 * Everything the validator knows about one adapter: the vendor whose tenant its
 * args must name, whether its `transform` reads `args.host` and fails the run
 * without it, whether it joins its vendor's leaves to a sibling geometry feed by
 * name, the further `args` keys its factory reads with no default, and the
 * complete `args` key set it accepts — a key outside that set is a boot error,
 * since a dead or misspelled arg used to boot cleanly and do nothing. A null
 * [acceptedArgKeys] leaves the adapter's args unjudged.
 */
internal data class AdapterPolicy(
    val tenantProvider: BookingProvider? = null,
    val requiresHost: Boolean = false,
    val joinsGeometry: Boolean = false,
    val requiredArgKeys: Set<String> = emptySet(),
    val acceptedArgKeys: Set<String>? = null,
) {
    /** [requiredArgKeys] plus the keys [tenantProvider] and [requiresHost] imply. */
    val mandatoryArgKeys: Set<String>
        get() =
            buildSet {
                addAll(requiredArgKeys)
                tenantProvider?.let { add(TENANT_ARG_KEYS.getValue(it)) }
                if (requiresHost) add(ARG_HOST)
            }
}

/**
 * One row per adapter the validator judges. A new adapter capability is a new
 * [AdapterPolicy] field, not a fifth adapter-keyed literal to keep in step.
 */
@Suppress("TopLevelPropertyNaming")
internal val ADAPTER_POLICIES =
    mapOf(
        ASPIRA_CAMPGROUNDS_ADAPTER to
            AdapterPolicy(
                tenantProvider = BookingProvider.ASPIRA,
                requiresHost = true,
                joinsGeometry = true,
                acceptedArgKeys = setOf(ARG_HOST, ARG_TENANT),
            ),
        BC_PARKS_CAMPGROUNDS_ADAPTER to
            AdapterPolicy(
                tenantProvider = BookingProvider.ASPIRA,
                requiresHost = true,
                joinsGeometry = true,
                acceptedArgKeys = setOf(ARG_HOST, ARG_TENANT),
            ),
        "AspiraCampsitesEtl" to
            AdapterPolicy(
                tenantProvider = BookingProvider.ASPIRA,
                requiredArgKeys = setOf(ARG_MAPS_INPUT, ARG_INVENTORY_INPUT),
                acceptedArgKeys =
                    setOf(ARG_TENANT, ARG_MAPS_INPUT, ARG_INVENTORY_INPUT, ARG_DICTIONARIES_INPUT, ARG_PARENT_DATA_PROVIDER),
            ),
        "ReserveAmericaCampgroundsEtl" to AdapterPolicy(tenantProvider = BookingProvider.RESERVEAMERICA),
        "ReserveAmericaSitesEtl" to AdapterPolicy(tenantProvider = BookingProvider.RESERVEAMERICA),
    )

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
//   - booking_providers: one row per booking vendor — the name a person calls
//     it, whether they book on its own site, and the tenants it runs.
//     Projected by TenantRegistry; no etls.
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
    @kotlinx.serialization.SerialName("booking_providers")
    val bookingProviders: List<BookingProviderEntry> = emptyList(),
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
        validateBookingProviders(errs)
        val poiRows = poiData.map { EtlRowRef(it.name, it.etls) }
        val campsiteRows = campsiteData.map { EtlRowRef(it.name, it.etls) }
        validateEtlSection(
            label = POI_DATA_SECTION,
            rows = poiRows,
            dsSlugs = dsSlugs,
            allEtlSlugs = etlSlugs,
            errs = errs,
        )
        validateEtlSection(
            label = CAMPSITE_DATA_SECTION,
            rows = campsiteRows,
            dsSlugs = dsSlugs,
            allEtlSlugs = etlSlugs,
            errs = errs,
        )
        validateAdapterPolicies(POI_DATA_SECTION, poiRows, errs)
        validateAdapterPolicies(CAMPSITE_DATA_SECTION, campsiteRows, errs)

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

    /**
     * The `booking_providers` section: one row per [BookingProvider] member,
     * every name and host a real string, at least one tenant per vendor, tenant
     * codes unique per vendor, hosts unique across the section, and every ETL
     * row that names a tenant naming a real one at the right vendor.
     */
    private fun validateBookingProviders(errs: MutableList<String>) {
        // Only this method's own errors may skip the cross-check below: an
        // unrelated typo elsewhere in the file must not cost a second boot.
        val before = errs.size
        val byProvider = bookingProviders.groupBy { it.id }
        for (provider in BookingProvider.entries) {
            val rows = byProvider[provider].orEmpty()
            if (rows.isEmpty()) errs += "booking_providers is missing a row for '${provider.id}'"
            if (rows.size > 1) errs += "booking_providers has ${rows.size} rows for '${provider.id}'"
        }
        val hosts = mutableSetOf<String>()
        for (entry in bookingProviders) {
            val vendor = entry.id.id
            if (entry.displayName.isBlank()) errs += "booking_providers '$vendor' has a blank display_name"
            // A vendor with no tenant can be named by no host, so the CTA guard
            // and every info-link label silently stop matching it.
            if (entry.tenants.isEmpty()) errs += "booking_providers '$vendor' declares no tenants"
            val codes = mutableSetOf<String?>()
            for (tenant in entry.tenants) {
                if (!codes.add(tenant.code)) {
                    errs += "booking_providers '$vendor' has duplicate tenant code '${tenant.code}'"
                }
                // Absent is how a single-tenant vendor says "no code"; blank is
                // a distinct key that nothing stores and nothing looks up.
                if (tenant.code != null && tenant.code.isBlank()) {
                    errs += "booking_providers '$vendor' has a blank tenant code (omit `code` instead)"
                }
                if (tenant.displayName != null && tenant.displayName.isBlank()) {
                    errs += "booking_providers '$vendor' tenant '${tenant.code}' has a blank display_name"
                }
                if (tenant.host.isBlank()) {
                    errs += "booking_providers '$vendor' tenant '${tenant.code}' has a blank host"
                    continue
                }
                val host = TenantRegistry.normalizeHost(tenant.host)
                if (!hosts.add(host)) errs += "duplicate booking_providers host '$host'"
            }
        }
        if (errs.size > before) return
        val registry = TenantRegistry.from(bookingProviders)
        validateEtlTenantArgs(POI_DATA_SECTION, poiData.map { EtlRowRef(it.name, it.etls) }, registry, errs)
        validateEtlTenantArgs(CAMPSITE_DATA_SECTION, campsiteData.map { EtlRowRef(it.name, it.etls) }, registry, errs)
    }

    private fun validateEtlTenantArgs(
        label: String,
        rows: List<EtlRowRef>,
        registry: TenantRegistry,
        errs: MutableList<String>,
    ) {
        for (row in rows) {
            for (etl in row.etls) {
                for (key in ADAPTER_POLICIES[etl.adapter]?.mandatoryArgKeys.orEmpty()) {
                    if (key !in etl.args) {
                        errs += "$label '${row.name}' etl '${etl.slug}' adapter '${etl.adapter}' " +
                            "is missing required arg '$key'"
                    }
                }
                for ((provider, argKey) in TENANT_ARG_KEYS) {
                    val code = etl.args[argKey] ?: continue
                    val tenant = registry.tenant(provider, code)
                    if (tenant == null) {
                        errs += "$label '${row.name}' etl '${etl.slug}' args.$argKey='$code' is not a tenant of '${provider.id}'"
                        continue
                    }
                    val declaredHost = etl.args[ARG_HOST] ?: continue
                    if (TenantRegistry.normalizeHost(declaredHost) != TenantRegistry.normalizeHost(tenant.host)) {
                        errs += "$label '${row.name}' etl '${etl.slug}' args.host='$declaredHost' " +
                            "does not match tenant '$code' host '${tenant.host}'"
                    }
                }
            }
        }
    }

    /**
     * The `geometry:` block and the closed `args` key set for the adapters that
     * join geometry by name. Runs after [validateBookingProviders] so nothing
     * here can suppress that method's own tenant cross-check.
     */
    private fun validateAdapterPolicies(
        label: String,
        rows: List<EtlRowRef>,
        errs: MutableList<String>,
    ) {
        for (row in rows) {
            for (etl in row.etls) {
                val where = "$label '${row.name}' etl '${etl.slug}'"
                val policy = ADAPTER_POLICIES[etl.adapter]
                policy?.acceptedArgKeys?.let { accepted ->
                    for (key in etl.args.keys - accepted) {
                        errs += "$where adapter '${etl.adapter}' does not accept arg '$key' " +
                            "(accepted: ${accepted.sorted().joinToString()})"
                    }
                }
                when {
                    policy == null ->
                        if (etl.geometry != null) {
                            errs += "$where adapter '${etl.adapter}' has no adapter policy, so it must not declare 'geometry'"
                        }

                    !policy.joinsGeometry ->
                        if (etl.geometry != null) {
                            errs += "$where adapter '${etl.adapter}' does not join geometry, so it must not declare 'geometry'"
                        }

                    else -> validateJoinedGeometry(where, etl, errs)
                }
            }
        }
    }

    /**
     * One geometry-joining row: the sibling role feeds its `parse` partitions
     * the inputs into, then the `geometry:` block itself.
     */
    private fun validateJoinedGeometry(
        where: String,
        etl: EtlEntry,
        errs: MutableList<String>,
    ) {
        validateInputRoles(where, etl, errs)
        val geometry = etl.geometry
        if (geometry == null || geometry.sources.isEmpty()) {
            errs += "$where adapter '${etl.adapter}' must declare 'geometry' with at least one source"
            return
        }
        validateGeometrySources(where, etl, geometry, errs)
        val threshold = geometry.match.fuzzyThreshold
        // Stated positively so NaN, which compares false to everything, falls into the error branch.
        if (!(threshold > MIN_FUZZY_THRESHOLD_EXCLUSIVE && threshold <= MAX_FUZZY_THRESHOLD_INCLUSIVE)) {
            errs += "$where match.fuzzy_threshold=$threshold is outside " +
                "($MIN_FUZZY_THRESHOLD_EXCLUSIVE, $MAX_FUZZY_THRESHOLD_INCLUSIVE]"
        }
    }

    /**
     * The run-time partition, checked at boot. A missing inventory feed is not a
     * lighter configuration: it empties the booking-CTA index, so every row is
     * written without its "Book" deep link while the run reports success.
     */
    private fun validateInputRoles(
        where: String,
        etl: EtlEntry,
        errs: MutableList<String>,
    ) {
        for (role in AspiraInputRoles.required) {
            val carrying = etl.inputs.filter { it.contains(role) }
            if (carrying.size != 1) {
                errs += "$where must declare exactly one '$role' input, got ${carrying.size} " +
                    "(inputs: ${etl.inputs.joinToString()})"
            }
        }
    }

    private fun validateGeometrySources(
        where: String,
        etl: EtlEntry,
        geometry: GeometryPolicy,
        errs: MutableList<String>,
    ) {
        val seen = mutableSetOf<String>()
        for (source in geometry.sources) {
            if (source.input !in etl.inputs) {
                errs += "$where geometry source input '${source.input}' is not one of the etl's inputs"
            }
            if (!seen.add(source.input)) {
                errs += "$where declares geometry source input '${source.input}' twice"
            }
            for (marker in AspiraInputRoles.all) {
                if (source.input.contains(marker)) {
                    errs += "$where geometry source input '${source.input}' carries the '$marker' role marker, " +
                        "so it would be claimed by geometry and never read as that role feed"
                }
            }
            validateSourceFilter(where, source, SOURCE_STATE_KEY, source.state, GeometryFormat.USCAMPGROUNDS_CSV, errs)
            validateSourceFilter(
                where,
                source,
                SOURCE_NAME_PROPERTY_KEY,
                source.nameProperty,
                GeometryFormat.GEOJSON_POINTS,
                errs,
            )
        }
        if (etl.adapter == BC_PARKS_CAMPGROUNDS_ADAPTER) {
            val only = geometry.sources.singleOrNull()
            if (only == null || only.format != GeometryFormat.BCPARKS_STRAPI) {
                errs += "$where adapter '$BC_PARKS_CAMPGROUNDS_ADAPTER' must declare exactly one geometry source " +
                    "with format '${GeometryFormat.BCPARKS_STRAPI.wire}'"
            }
        }
    }

    /**
     * One optional per-source filter: present only on the format that honours
     * it, and never present-but-empty. A blank filter matches nothing, so it
     * would empty the join instead of narrowing it.
     */
    private fun validateSourceFilter(
        where: String,
        source: GeometrySourceSpec,
        key: String,
        value: String?,
        honouredBy: GeometryFormat,
        errs: MutableList<String>,
    ) {
        if (value == null) return
        if (source.format != honouredBy) {
            errs += "$where geometry source '${source.input}' declares '$key', " +
                "which only '${honouredBy.wire}' honours"
        }
        if (value.isBlank()) {
            errs += "$where geometry source '${source.input}' declares a blank '$key'"
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

    companion object {
        private val yaml =
            Yaml(
                configuration =
                    com.charleskorn.kaml.YamlConfiguration(strictMode = true),
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
