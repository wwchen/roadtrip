package ca.floo.roadtrip.etl.registry

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import java.io.File

// In-memory representation of config/poi-registry.yaml. Loaded once at
// boot, used by:
//   1. PoiRegistrySync — UPSERTs governing_body + booking_provider rows
//      from this into the DB (and refuses to boot if YAML deletes a slug
//      that's still referenced by pois rows).
//   2. EtlOrchestrator — looks up the source's governing_body slug + the
//      booking_provider FK (if any) when transforming.
//
// Adding a new source = appending one entry under the relevant
// governing_body's `sources:` list. Adding a new vendor = one
// `booking_providers:` entry plus a Kotlin ETL impl + EtlOrchestrator
// registration. No Flyway migration needed for either.
@Serializable
data class PoiRegistry(
    @kotlinx.serialization.SerialName("governing_bodies")
    val governingBodies: List<GoverningBodyEntry>,
    @kotlinx.serialization.SerialName("booking_providers")
    val bookingProviders: List<BookingProviderEntry>,
) {
    companion object {
        private val yaml =
            Yaml(
                configuration =
                    com.charleskorn.kaml.YamlConfiguration(strictMode = false),
            )

        fun load(file: File): PoiRegistry = yaml.decodeFromString(serializer(), file.readText())
    }

    /** Look up by slug — used by ETL transformers. Throws if absent. */
    fun governingBody(slug: String): GoverningBodyEntry =
        governingBodies.firstOrNull { it.slug == slug }
            ?: error(
                "governing body slug=$slug not in config/poi-registry.yaml — " +
                    "add a row there",
            )

    /** Aspira-style multi-host vendors disambiguate by host. */
    fun bookingProvider(
        vendor: String,
        host: String?,
    ): BookingProviderEntry =
        bookingProviders.firstOrNull { it.vendor == vendor && it.host == host }
            ?: error(
                "booking_provider (vendor=$vendor, host=$host) not in " +
                    "config/poi-registry.yaml",
            )
}

@Serializable
data class GoverningBodyEntry(
    val slug: String,
    val name: String,
    val kind: String, // federal | state | provincial | local | private | corporate
    val country: String? = null, // ISO 3166-1 alpha-2; null for cross-border vendors
    val sources: List<SourceEntry> = emptyList(),
)

@Serializable
data class SourceEntry(
    val id: String, // matches data/raw/<id>/, also pois.source
    val fetcher: String, // scripts/<fetcher>.py
    val provides: String, // 'campground' | 'planet-fitness' | 'aspira-id-index' | etc.
    val args: Map<String, String> = emptyMap(),
    @kotlinx.serialization.SerialName("booking_provider")
    val bookingProvider: BookingProviderRef? = null,
    @kotlinx.serialization.SerialName("depends_on")
    val dependsOn: List<String> = emptyList(),
)

@Serializable
data class BookingProviderRef(
    val vendor: String,
    val host: String? = null,
)

@Serializable
data class BookingProviderEntry(
    val vendor: String,
    val host: String? = null,
    val name: String,
    @kotlinx.serialization.SerialName("adapter_class")
    val adapterClass: String, // empty string = no adapter implemented yet
)
