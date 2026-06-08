package ca.floo.roadtrip.etl.registry

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import java.io.File

// In-memory representation of config/poi-registry.yaml. Loaded once at
// boot, used by:
//   1. PoiRegistrySync — UPSERTs booking_provider rows from this into the
//      DB (and refuses to boot if YAML deletes a (vendor, host) that's
//      still referenced by pois rows).
//   2. EtlOrchestrator / IngestController — looks up the source's
//      etl_adapter to decide whether to run the ETL or skip the import
//      phase entirely (no-import for adapters not yet implemented).
//   3. RegistryTargets — flattens enabled data_sources into IngestController
//      targets (one per source).
//
// Adding a new source = appending one entry under `data_sources`. Adding
// a new vendor = a new `data_provider` block on a source plus a Kotlin
// adapter class. No Flyway migration needed for either.
@Serializable
data class PoiRegistry(
    @kotlinx.serialization.SerialName("data_sources")
    val dataSources: List<DataSourceEntry>,
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
     * dangling references at boot rather than at first row-insert.
     *
     * Today's checks:
     *   - data_source slugs are unique
     *   - depends_on references point at a declared slug
     */
    fun validate() {
        val errs = mutableListOf<String>()
        val slugSeen = mutableSetOf<String>()
        for (s in dataSources) {
            if (!slugSeen.add(s.slug)) errs += "duplicate data_source slug='${s.slug}'"
        }
        val allSlugs = dataSources.map { it.slug }.toSet()
        for (s in dataSources) {
            for (dep in s.dependsOn) {
                if (dep !in allSlugs) {
                    errs += "data_source '${s.slug}'.depends_on='$dep' is not a declared slug"
                }
            }
        }
        require(errs.isEmpty()) {
            "config/poi-registry.yaml has ${errs.size} validation error(s):\n" +
                errs.joinToString("\n") { "  - $it" }
        }
    }

    /** Sources to run on a no-target fan-out / drive ETL imports. */
    fun enabledSources(): List<DataSourceEntry> = dataSources.filter { it.enabled }

    /**
     * The deduped (vendor, host) booking-provider set across every
     * declared data_source's `data_provider` block. The DB sync UPSERTs
     * one row per element.
     */
    fun bookingProviders(): List<DataProvider> {
        val seen = LinkedHashMap<Pair<String, String?>, DataProvider>()
        for (s in dataSources) {
            val p = s.dataProvider ?: continue
            val key = p.vendor to p.host
            // First wins — later entries with the same (vendor, host) are
            // deduped silently. Display name and adapter are expected to
            // agree across sources that share a provider.
            seen.putIfAbsent(key, p)
        }
        return seen.values.toList()
    }
}

@Serializable
data class DataSourceEntry(
    val slug: String,
    val name: String,
    val enabled: Boolean = true,
    val category: String,
    // Sub-bucket for the FE campground layer's circle-color expression
    // and per-bucket legend toggles: federal | state | local | provincial.
    // Static stamp — every row from this source gets this bucket.
    // Sources whose bucket varies per row (uscampgrounds reads it from
    // CSV typeLabel) leave this null and let the ETL stamp per-row.
    @kotlinx.serialization.SerialName("legend_bucket")
    val legendBucket: String? = null,
    val fetcher: Fetcher,
    @kotlinx.serialization.SerialName("etl_adapter")
    val etlAdapter: String? = null,
    @kotlinx.serialization.SerialName("data_provider")
    val dataProvider: DataProvider? = null,
    @kotlinx.serialization.SerialName("depends_on")
    val dependsOn: List<String> = emptyList(),
)

@Serializable
data class Fetcher(
    val executor: String,
    val filename: String,
    // false = the upstream is currently unreachable / requires host-only
    // setup (curl-impersonate + fresh cookies, etc). Skip fetch but still
    // run import against any existing raw captures. The parent
    // DataSourceEntry.enabled flag still wins — disabling the data_source
    // turns off both fetch and import; this flag only narrows fetch.
    val enabled: Boolean = true,
    val args: Map<String, String> = emptyMap(),
    @kotlinx.serialization.SerialName("output_dir_prefix")
    val outputDirPrefix: String,
)

@Serializable
data class DataProvider(
    val vendor: String,
    val host: String? = null,
    val name: String,
    // Empty string = no adapter implemented yet; availability returns
    // no_provider until one ships.
    val adapter: String = "",
)
