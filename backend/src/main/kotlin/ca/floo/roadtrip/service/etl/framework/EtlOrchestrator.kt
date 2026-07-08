package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.models.domain.Poi
import ca.floo.roadtrip.models.metadata.Envelope
import ca.floo.roadtrip.models.metadata.ValidationResult
import ca.floo.roadtrip.models.metadata.registry.AgencyConfig
import ca.floo.roadtrip.models.metadata.registry.EtlEntry
import ca.floo.roadtrip.models.metadata.registry.PoiDataEntry
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.CanonicalCatalogRepo
import ca.floo.roadtrip.repo.NoCaptureException
import ca.floo.roadtrip.repo.RawCapture
import ca.floo.roadtrip.repo.Upsert
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.io.File

private const val DISABLED_JOINER_IMPORT_MESSAGE =
    "vendor campsite parent joiners are disabled until canonical campground/campsite reconciliation is wired"

// Orchestrates one poi_data/reservable_data row's ETL chain end-to-end.
//
// Per-row sequence (declared etls: list, in order):
//   1. Resolve each etls entry's `inputs:` slug into either:
//        - data_source: newest envelope(s) under data/raw/<slug>/
//        - earlier sibling etl in the SAME poi_data row: typed payload
//          handed off in-memory by the previous stage.
//   2. Hand the InputBundle to the etl.parse → validate → transform stages.
//   3. If the etl is intermediate (not the last in the row), keep OUT in
//      the per-run map for later siblings to consume. No disk persistence —
//      every ETL is f(inputs) → output, so re-running an import recomputes.
//   4. If terminal, persist the supported catalog output:
//        - CampgroundEtlOutput -> canonical campgrounds + lean POI wrappers
//        - CampsiteEtlOutput   -> canonical campsites
//        - TeslaSuperchargerEtlOutput -> canonical Tesla locations + lean POI wrappers
//        - PlanetFitnessLocationEtlOutput -> canonical PF locations + lean POI wrappers
//        - List<Poi.*>         -> retired wide-POI path, only for disabled
//          legacy adapters/tests
//
// The DAG-level ordering across multiple poi_data rows is the caller's
// problem (today: per-row imports, no cross-row composition). Within a row,
// list order = dependency order, validated at boot.
class EtlOrchestrator(
    private val ctx: DSLContext,
    private val rawDir: File,
    private val poiRegistry: PoiRegistry,
    /**
     * ETL adapter map keyed by YAML slug. Defaults to the production
     * registry under [Companion.registry]; overridable for tests.
     */
    private val etlRegistry: Map<String, SourceEtl<*, *>> = registry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val upsert = Upsert(ctx)
    private val catalogRepo = CanonicalCatalogRepo(ctx)

    /**
     * Per-row run summary for import rows. `poi_data` and `reservable_data`
     * both flow through the same chain runner; only terminal persistence
     * differs by output type.
     */
    data class Stats(
        val poiDataName: String,
        val terminalEtlSlug: String,
        val parsed: Int,
        val transformed: Int,
        val upsertResult: CanonicalCatalogRepo.Result,
    )

    data class ReservableStats(
        val reservableDataName: String,
        val terminalEtlSlug: String,
        val runId: Long,
        val parsed: Int,
        val upserted: Int,
        val swept: Int,
    )

    data class JoinerStats(
        val joinerName: String,
        val adapter: String,
        val linksDiscovered: Int,
        val linksInserted: Int,
        val staleLinksDeleted: Int,
    )

    /**
     * Run a poi_data row by display name. Walks the row's `etls:` chain in
     * declared order, materializing intermediates and persisting the
     * terminal catalog output. Throws if the row isn't registered or any
     * stage fails.
     */
    fun runPoiData(name: String): Stats {
        val row =
            poiRegistry.poiDataByName(name)
                ?: error("no poi_data row with name='$name'")
        require(row.etls.isNotEmpty()) { "poi_data '$name' has empty etls list" }

        log.info("etl poi_data='{}' starting ({} stages)", name, row.etls.size)
        return runEtlChain(
            rowName = row.name,
            sectionLabel = "poi_data",
            etls = row.etls,
            poiDataEntry = row,
        )
    }

    fun runReservableData(name: String): ReservableStats {
        val row =
            poiRegistry.reservableDataByName(name)
                ?: error("no reservable_data row with name='$name'")
        require(row.etls.isNotEmpty()) { "reservable_data '$name' has empty etls list" }

        log.info("etl reservable_data='{}' starting ({} stages)", name, row.etls.size)
        val stats =
            runEtlChain(
                rowName = row.name,
                sectionLabel = "reservable_data",
                etls = row.etls,
                poiDataEntry = null,
            )
        return ReservableStats(
            reservableDataName = row.name,
            terminalEtlSlug = stats.terminalEtlSlug,
            runId = stats.upsertResult.runId,
            parsed = stats.parsed,
            upserted = stats.upsertResult.upsertedCount,
            swept = stats.upsertResult.sweptCount,
        )
    }

    private fun runEtlChain(
        rowName: String,
        sectionLabel: String,
        etls: List<EtlEntry>,
        poiDataEntry: PoiDataEntry?,
    ): Stats {
        val transformCtx = TransformCtx.load(rawDir, poiRegistry)

        // Per-run cache of intermediate outputs keyed by etl slug. Lets a
        // later sibling read a just-written intermediate without going back
        // through the filesystem.
        val intermediateOutputs = mutableMapOf<String, JsonElement>()

        var terminalStats: Stats? = null

        for ((index, entry) in etls.withIndex()) {
            val isTerminal = index == etls.lastIndex
            val etl =
                etlRegistry[entry.slug]
                    ?: error("no adapter registered for etl slug='${entry.slug}'")
            log.info(
                "  stage {}/{} slug={} adapter={} terminal={}",
                index + 1,
                etls.size,
                entry.slug,
                etl::class.simpleName,
                isTerminal,
            )

            val bundle = buildBundle(entry.inputs, intermediateOutputs)
            if (isTerminal) {
                terminalStats = runTerminal(rowName, sectionLabel, poiDataEntry, etl, bundle, transformCtx)
            } else {
                intermediateOutputs[entry.slug] = runIntermediate(etl, bundle, transformCtx)
            }
        }

        return terminalStats!!
    }

    fun runJoiner(name: String): JoinerStats = throw UnsupportedOperationException("$DISABLED_JOINER_IMPORT_MESSAGE: $name")

    @Suppress("UNCHECKED_CAST")
    private fun runTerminal(
        rowName: String,
        sectionLabel: String,
        poiDataEntry: PoiDataEntry?,
        etl: SourceEtl<*, *>,
        bundle: InputBundle,
        transformCtx: TransformCtx,
    ): Stats {
        val concrete = etl as SourceEtl<Any, Any>
        val dto = concrete.parse(bundle)
        val validated =
            when (val v = concrete.validate(dto)) {
                is ValidationResult.Ok -> v.dto
                is ValidationResult.Bad -> {
                    log.warn("{} '{}' terminal validation failed: {}", sectionLabel, rowName, v.errors)
                    return Stats(
                        poiDataName = rowName,
                        terminalEtlSlug = concrete.etlSlug,
                        parsed = 0,
                        transformed = 0,
                        upsertResult =
                            CanonicalCatalogRepo.Result(
                                runId = -1L,
                                seenCount = 0,
                                upsertedCount = 0,
                            ),
                    )
                }
            }
        val output = concrete.transform(validated, transformCtx)
        val ups =
            when (output) {
                is CampgroundEtlOutput ->
                    catalogRepo.upsertCampgrounds(output.campgrounds, source = concrete.etlSlug)
                is CampsiteEtlOutput ->
                    catalogRepo.upsertCampsites(output.campsites, source = concrete.etlSlug)
                is TeslaSuperchargerEtlOutput ->
                    catalogRepo.upsertTeslaSuperchargers(output.superchargers, source = concrete.etlSlug)
                is PlanetFitnessLocationEtlOutput ->
                    catalogRepo.upsertPlanetFitnessLocations(output.locations, source = concrete.etlSlug)
                is List<*> -> {
                    val pois = output.filterIsInstance<Poi>()
                    check(pois.size == output.size) {
                        "terminal '${concrete.etlSlug}' returned unsupported List element type"
                    }
                    poiDataEntry?.let { validateAgencyConfig(it, pois) }
                    val legacy = upsert.run(setOf(concrete.etlSlug), pois)
                    CanonicalCatalogRepo.Result(
                        runId = legacy.runId,
                        seenCount = legacy.seenCount,
                        upsertedCount = legacy.seenCount,
                        sweptCount = legacy.sweptCount,
                    )
                }
                else -> error("terminal '${concrete.etlSlug}' returned unsupported output ${output::class.qualifiedName}")
            }
        val transformedCount = outputCount(output)
        log.info(
            "{} '{}' terminal slug={} transformed={} upserted={} swept={}",
            sectionLabel,
            rowName,
            concrete.etlSlug,
            transformedCount,
            ups.upsertedCount,
            ups.sweptCount,
        )
        return Stats(
            poiDataName = rowName,
            terminalEtlSlug = concrete.etlSlug,
            parsed = transformedCount,
            transformed = transformedCount,
            upsertResult = ups,
        )
    }

    private fun outputCount(output: Any): Int =
        when (output) {
            is CampgroundEtlOutput -> output.campgrounds.size
            is CampsiteEtlOutput -> output.campsites.size
            is TeslaSuperchargerEtlOutput -> output.superchargers.size
            is PlanetFitnessLocationEtlOutput -> output.locations.size
            is List<*> -> output.size
            else -> 0
        }

    private fun validateAgencyConfig(
        row: PoiDataEntry,
        pois: List<Poi>,
    ) {
        when (val agency = row.agency) {
            null -> return
            is AgencyConfig.Constant -> {
                val mismatched = pois.count { it.agency != agency.value }
                check(mismatched == 0) {
                    "poi_data '${row.name}' agency=${agency.value} but $mismatched terminal POI(s) had a different agency"
                }
            }
            is AgencyConfig.DerivedFromField -> {
                // Per-row null tolerance: upstream feeds (e.g. RIDB) ship occasional
                // rows with the agency path missing or non-scalar. We log the count
                // rather than aborting the whole import — a single bad row mustn't
                // sink the dataset.
                val missing = pois.count { it.agency.isNullOrBlank() }
                if (missing > 0) {
                    log.warn(
                        "poi_data '{}' agency derived_from_field={} produced {} terminal POI(s) with null agency",
                        row.name,
                        agency.field,
                        missing,
                    )
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runIntermediate(
        etl: SourceEtl<*, *>,
        bundle: InputBundle,
        transformCtx: TransformCtx,
    ): JsonElement {
        val concrete = etl as SourceEtl<Any, Any>
        val dto = concrete.parse(bundle)
        val validated =
            when (val v = concrete.validate(dto)) {
                is ValidationResult.Ok -> v.dto
                is ValidationResult.Bad -> error("intermediate '${concrete.etlSlug}' validation failed: ${v.errors}")
            }
        val out = concrete.transform(validated, transformCtx)
        // The orchestrator doesn't know the OUT type at compile time —
        // Json.encodeToJsonElement requires a serializer. We rely on the
        // adapter implementing Json-friendly types (kotlinx.serialization
        // @Serializable). Use kotlinx-json's polymorphic-by-reflection
        // path: encode via the runtime serializer of the value's class.
        return Json.Default.encodeToJsonElement(serializerForValue(out), out)
    }

    private fun buildBundle(
        inputSlugs: List<String>,
        intermediateOutputs: Map<String, JsonElement>,
    ): InputBundle {
        val raw = LinkedHashMap<String, List<Envelope>>()
        val etls = LinkedHashMap<String, JsonElement>()
        for (slug in inputSlugs) {
            val ds = poiRegistry.dataSource(slug)
            if (ds != null) {
                // data_source input: load envelope(s) from data/raw/<slug>/
                raw[slug] = loadDataSourceEnvelopes(slug)
            } else if (slug in intermediateOutputs) {
                // sibling intermediate from earlier in this same row's
                // etls: chain. PoiRegistry's validator rejects cross-row
                // refs, so anything not here-or-data_source is unreachable.
                etls[slug] = intermediateOutputs[slug]!!
            } else {
                error(
                    "input '$slug' is neither a data_source nor a prior sibling etl in this row — should have been caught by PoiRegistry.validate()",
                )
            }
        }
        return InputBundle(raw, etls)
    }

    private fun loadDataSourceEnvelopes(slug: String): List<Envelope> {
        val dir = File(rawDir, slug)
        if (!dir.isDirectory) throw NoCaptureException("$dir is not a directory")
        // Auto-detect single-file vs directory-of-pages by inspecting the
        // newest entry. A single-file capture is one envelope; a directory
        // is a multipart capture and we return all its pages.
        val newest =
            dir.listFiles()?.maxByOrNull { it.name }
                ?: throw NoCaptureException("no captures under $dir")
        return if (newest.isDirectory) {
            val pages =
                newest
                    .listFiles { f -> f.isFile && f.name.endsWith(".json") }
                    ?.sortedBy { it.name } ?: emptyList()
            if (pages.isEmpty()) throw NoCaptureException("no pages under $newest")
            pages.map { RawCapture.parseEnvelope(it) }
        } else {
            listOf(RawCapture.parseEnvelope(newest))
        }
    }

    companion object {
        // Runnable ETLs. Old wide-POI and retired-reservable imports are
        // intentionally not exposed here; admin import targets use this map
        // to decide what can run. Campflare and Canada sources are enabled
        // because they write through the canonical catalog repo.
        val registry: Map<String, SourceEtl<*, *>> =
            mapOf(
                "campflare-campgrounds" to
                    ca.floo.roadtrip.service.etl.vendors.campflare
                        .CampflareCampgroundsEtl(),
                "campflare-campsites" to
                    ca.floo.roadtrip.service.etl.vendors.campflare
                        .CampflareCampsitesEtl(),
                "aspira-leaves-bc" to
                    ca.floo.roadtrip.service.etl.vendors.aspira
                        .AspiraLeavesEtl("aspira-leaves-bc"),
                "aspira-bc-pins" to
                    ca.floo.roadtrip.service.etl.vendors.aspira
                        .AspiraJoinByNameEtl("aspira-bc-pins"),
                "aspira-leaves-pc" to
                    ca.floo.roadtrip.service.etl.vendors.aspira
                        .AspiraLeavesEtl("aspira-leaves-pc"),
                "aspira-pc-pins" to
                    ca.floo.roadtrip.service.etl.vendors.aspira
                        .AspiraJoinByNameEtl("aspira-pc-pins"),
                "alberta-provincial" to
                    ca.floo.roadtrip.service.etl.vendors.reserveamerica
                        .ReserveAmericaEtl(),
                "aspira-bc-resources" to
                    ca.floo.roadtrip.service.etl.vendors.aspira
                        .AspiraResourcesEtl(
                            etlSlug = "aspira-bc-resources",
                            mapsInputSlug = "aspira-maps-bc",
                            inventoryInputSlug = "aspira-inventory-bc",
                            dictionariesInputSlug = "aspira-dictionaries-bc",
                            vendor = "aspira_bc",
                        ),
                "aspira-pc-resources" to
                    ca.floo.roadtrip.service.etl.vendors.aspira
                        .AspiraResourcesEtl(
                            etlSlug = "aspira-pc-resources",
                            mapsInputSlug = "aspira-maps-pc",
                            inventoryInputSlug = "aspira-inventory-pc",
                            dictionariesInputSlug = "aspira-dictionaries-pc",
                            vendor = "aspira_pc",
                        ),
                "alberta-provincial-park-sites" to
                    ca.floo.roadtrip.service.etl.vendors.reserveamerica
                        .ReserveAmericaSitesEtl("alberta-provincial-park-sites", "ABPP"),
                "planet-fitness" to
                    ca.floo.roadtrip.service.etl.vendors.osmpf
                        .PlanetFitnessEtl(),
                "tesla-superchargers" to
                    ca.floo.roadtrip.service.etl.vendors.tesla
                        .TeslaIndexEtl(),
            )

        // Retained vendor adapters. This keeps the parsing/transform code in
        // tree while making it explicit that the old registry rows are no-op
        // until canonical campgrounds/campsites upsert support lands.
        val disabledVendorRegistry: Map<String, SourceEtl<*, *>> =
            mapOf(
                "bcparks-strapi" to
                    ca.floo.roadtrip.service.etl.vendors.bcparks
                        .BcParksStrapiEtl(),
                "new-york-state-parks" to
                    ca.floo.roadtrip.service.etl.vendors.reserveamerica
                        .ReserveAmericaEtl("new-york-state-parks"),
                "california-state-parks" to
                    ca.floo.roadtrip.service.etl.vendors.reservecalifornia
                        .ReserveCaliforniaEtl("california-state-parks"),
                // RIDB (recreation.gov backend) — one ETL covers every
                // publishing agency (NPS, USFS, BLM, USACE, FWS, BOR, TVA, …).
                // Per-facility agency stamped on Poi.Campground.agency at
                // transform time from ORGANIZATION[0].OrgName.
                "federal-campgrounds" to
                    ca.floo.roadtrip.service.etl.vendors.recgov
                        .RecGovCampgroundsEtl("federal-campgrounds"),
                // Aspira NextGen — one leaf-walker + one join-by-name
                // emitter per tenant. Both classes take the slug as a
                // constructor arg so a fourth tenant is two YAML rows +
                // two registry lines.
                "aspira-leaves-wa" to
                    ca.floo.roadtrip.service.etl.vendors.aspira
                        .AspiraLeavesEtl("aspira-leaves-wa"),
                "aspira-wa-pins" to
                    ca.floo.roadtrip.service.etl.vendors.aspira
                        .AspiraJoinByNameEtl("aspira-wa-pins"),
                "federal-campsites" to
                    ca.floo.roadtrip.service.etl.vendors.recgov
                        .RecGovCampsitesEtl("federal-campsites"),
                "aspira-wa-resources" to
                    ca.floo.roadtrip.service.etl.vendors.aspira
                        .AspiraResourcesEtl(
                            etlSlug = "aspira-wa-resources",
                            mapsInputSlug = "aspira-maps-wa",
                            inventoryInputSlug = "aspira-inventory-wa",
                            dictionariesInputSlug = "aspira-dictionaries-wa",
                            vendor = "aspira_wa",
                        ),
                "new-york-state-park-sites" to
                    ca.floo.roadtrip.service.etl.vendors.reserveamerica
                        .ReserveAmericaSitesEtl("new-york-state-park-sites", "NY"),
            )
    }
}

/**
 * Reflection-based runtime serializer lookup. Falls back to a synthetic
 * "wrap-as-JsonObject" serializer if the value's class isn't @Serializable
 * — which would only happen for an intermediate ETL whose author didn't
 * tag the output type. We'd want to fail loud in that case.
 */
@Suppress("UNCHECKED_CAST")
private fun serializerForValue(value: Any): kotlinx.serialization.KSerializer<Any> =
    kotlinx.serialization.serializer(value::class.java)
        as kotlinx.serialization.KSerializer<Any>
