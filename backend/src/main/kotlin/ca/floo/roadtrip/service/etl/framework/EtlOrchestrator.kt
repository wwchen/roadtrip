package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.models.domain.CatalogUpsertResult
import ca.floo.roadtrip.models.metadata.Envelope
import ca.floo.roadtrip.models.metadata.ValidationResult
import ca.floo.roadtrip.models.metadata.registry.EtlEntry
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteParentJoinerRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.CanonicalViewRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.RawCaptureRepo
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraCampsiteParentJoiner
import ca.floo.roadtrip.service.etl.vendors.recgov.RecgovCampsiteParentJoiner
import ca.floo.roadtrip.service.etl.vendors.reserveamerica.ReserveAmericaCampsiteParentJoiner
import ca.floo.roadtrip.service.etl.vendors.reservecalifornia.ReserveCaliforniaCampsiteParentJoiner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.io.File

// Orchestrates one poi_data/campsite_data row's ETL chain end-to-end.
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
//
// The DAG-level ordering across multiple poi_data rows is the caller's
// problem (today: per-row imports, no cross-row composition). Within a row,
// list order = dependency order, validated at boot.
open class EtlOrchestrator(
    private val ctx: DSLContext,
    private val rawDir: File,
    private val poiRegistry: PoiRegistry,
    /**
     * ETL adapter map keyed by YAML slug. Defaults to the production
     * registry under [Companion.registry]; overridable for tests.
     */
    private val etlRegistry: Map<String, SourceEtl<*, *>> = registry,
    /**
     * Joiner adapter map keyed by YAML `adapter:` name. Defaults to the
     * production registry under [Companion.joinerRegistry]; overridable
     * for tests.
     */
    private val joinerRegistry: Map<String, CampsiteParentJoiner> = Companion.joinerRegistry,
    /** Canonical materialized view refresh repo. */
    private val canonicalViews: CanonicalViewRepo = CanonicalViewRepo(ctx),
    /**
     * Repo for post-import campsite parent lookup and reparent writes. Kept
     * injectable so tests can still exercise chunk retry behavior through the
     * same repo boundary as production.
     */
    private val campsiteParentJoiners: CampsiteParentJoinerRepo = CampsiteParentJoinerRepo(ctx),
    private val joinerChunkSize: Int = DEFAULT_JOINER_CHUNK_SIZE,
) {
    init {
        require(joinerChunkSize > 0) { "joinerChunkSize must be positive" }
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val campgroundRepo = CampgroundRepo(ctx)
    private val campsiteRepo = CampsiteRepo(ctx)
    private val teslaSuperchargerRepo = TeslaSuperchargerRepo(ctx)
    private val planetFitnessLocationRepo = PlanetFitnessLocationRepo(ctx)
    private val rawCaptureRepo = RawCaptureRepo(rawDir)

    /**
     * Per-row run summary for import rows. `poi_data` and `campsite_data`
     * both flow through the same chain runner; only terminal persistence
     * differs by output type.
     */
    data class Stats(
        val poiDataName: String,
        val terminalEtlSlug: String,
        val parsed: Int,
        val transformed: Int,
        val upsertResult: CatalogUpsertResult,
    )

    data class CampsiteStats(
        val campsiteDataName: String,
        val terminalEtlSlug: String,
        val runId: Long,
        val parsed: Int,
        val upserted: Int,
        val skipped: Int,
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
        )
    }

    fun runCampsiteData(name: String): CampsiteStats {
        val row =
            poiRegistry.campsiteDataByName(name)
                ?: error("no campsite_data row with name='$name'")
        require(row.etls.isNotEmpty()) { "campsite_data '$name' has empty etls list" }

        log.info("etl campsite_data='{}' starting ({} stages)", name, row.etls.size)
        val stats =
            runEtlChain(
                rowName = row.name,
                sectionLabel = "campsite_data",
                etls = row.etls,
            )
        return CampsiteStats(
            campsiteDataName = row.name,
            terminalEtlSlug = stats.terminalEtlSlug,
            runId = stats.upsertResult.runId,
            parsed = stats.parsed,
            upserted = stats.upsertResult.upsertedCount,
            skipped = stats.upsertResult.skippedCount,
            swept = stats.upsertResult.sweptCount,
        )
    }

    private fun runEtlChain(
        rowName: String,
        sectionLabel: String,
        etls: List<EtlEntry>,
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
                terminalStats = runTerminal(rowName, sectionLabel, etl, bundle, transformCtx)
            } else {
                intermediateOutputs[entry.slug] = runIntermediate(etl, bundle, transformCtx)
            }
        }

        return terminalStats!!
    }

    /**
     * Run a campsite_parent_joiner row by name. The joiner walks the canonical
     * schema through its vendor-specific predicates (typically campsite
     * vendor refs → campground vendor refs) and returns a list of
     * (campsite_id, campground_id) pairs it believes are correct.
     *
     * `campsites.campground_id` is the durable parent link; where a
     * discovered pair disagrees with the current value we reparent the
     * campsite. A campsite not in the joiner's result set is out of scope
     * for that joiner and its parent is left alone — vendor scoping in
     * each repo query is what prevents one joiner from
     * clobbering another vendor's rows.
     *
     * This is a reconciliation pass: if `upsertCampsite` already resolved
     * the correct parent via `parentVendorRefId`, the UPDATE matches zero
     * rows and `linksInserted = 0`. It earns its keep when vendor payloads
     * shift over time (Aspira leaf reassignment, rec.gov facility moves)
     * or when future cross-vendor merges expose a better parent than the
     * source-of-truth ETL saw at write time.
     */
    fun runJoiner(name: String): JoinerStats {
        val row =
            poiRegistry.campsiteParentJoinerByName(name)
                ?: error("no campsite_parent_joiner row with name='$name'")
        val joiner =
            joinerRegistry[row.adapter]
                ?: error("no joiner adapter registered for '${row.adapter}'")

        log.info("joiner '{}' starting adapter={}", row.name, row.adapter)
        val joinerCtx = JoinerCtx(repo = campsiteParentJoiners, args = row.args)
        val links = joiner.discoverLinks(joinerCtx)
        var reparented = 0
        val chunks = links.chunked(joinerChunkSize)
        for ((chunkIndex, chunk) in chunks.withIndex()) {
            try {
                reparented += campsiteParentJoiners.reparentCampsites(chunk)
            } catch (e: Exception) {
                log.error(
                    "joiner '{}' adapter={} failed at chunk {}/{} after committing {} reparent(s); retrying the joiner resumes idempotently",
                    row.name,
                    row.adapter,
                    chunkIndex + 1,
                    chunks.size,
                    reparented,
                    e,
                )
                throw e
            }
        }
        val staleDeleted = joiner.sweepStaleLinks(joinerCtx)
        log.info(
            "joiner '{}' adapter={} discovered={} reparented={} stale_deleted={}",
            row.name,
            row.adapter,
            links.size,
            reparented,
            staleDeleted,
        )
        if (reparented > 0) {
            log.warn(
                "joiner '{}' reparented {} campsite(s) — upsert-time parent didn't match vendor-ref lookup",
                row.name,
                reparented,
            )
        }
        return JoinerStats(
            joinerName = row.name,
            adapter = row.adapter,
            linksDiscovered = links.size,
            linksInserted = reparented,
            staleLinksDeleted = staleDeleted,
        )
    }

    open fun refreshCanonicalViews() {
        canonicalViews.refreshCanonicalViews()
        log.info("canonical views refreshed")
    }

    @Suppress("UNCHECKED_CAST")
    private fun runTerminal(
        rowName: String,
        sectionLabel: String,
        etl: SourceEtl<*, *>,
        bundle: InputBundle,
        transformCtx: TransformCtx,
    ): Stats {
        val concrete = etl as SourceEtl<Any, Any>
        val dto = concrete.parse(bundle)
        val validated =
            when (val v = concrete.validate(dto)) {
                is ValidationResult.Ok -> v.dto
                is ValidationResult.Bad ->
                    error("$sectionLabel '$rowName' terminal '${concrete.etlSlug}' validation failed: ${v.errors}")
            }
        val output = concrete.transform(validated, transformCtx)
        val ups =
            when (output) {
                is CampgroundEtlOutput ->
                    campgroundRepo.upsertCampgrounds(output.campgrounds, source = concrete.etlSlug)
                is CampsiteEtlOutput ->
                    campsiteRepo.upsertCampsites(output.campsites, source = concrete.etlSlug)
                is TeslaSuperchargerEtlOutput ->
                    teslaSuperchargerRepo.upsertTeslaSuperchargers(output.superchargers, source = concrete.etlSlug)
                is PlanetFitnessLocationEtlOutput ->
                    planetFitnessLocationRepo.upsertPlanetFitnessLocations(output.locations, source = concrete.etlSlug)
                else -> error("terminal '${concrete.etlSlug}' returned unsupported output ${output::class.qualifiedName}")
            }
        val transformedCount = outputCount(output)
        log.info(
            "{} '{}' terminal slug={} transformed={} upserted={} skipped={} swept={}",
            sectionLabel,
            rowName,
            concrete.etlSlug,
            transformedCount,
            ups.upsertedCount,
            ups.skippedCount,
            ups.sweptCount,
        )
        if (ups.skippedCount > 0) {
            log.warn(
                "{} '{}' terminal slug={} skipped {} records (missing parent vendor ref or other row-level guard)",
                sectionLabel,
                rowName,
                concrete.etlSlug,
                ups.skippedCount,
            )
        }
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
            else -> 0
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
                raw[slug] = rawCaptureRepo.loadNewestEnvelopes(slug)
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

    companion object {
        private const val DEFAULT_JOINER_CHUNK_SIZE = 5000

        // Runnable ETLs. Admin import targets use this map to decide what can
        // run; every configured campground/campsite vendor row exposed here
        // writes through the canonical catalog repo.
        val registry: Map<String, SourceEtl<*, *>> =
            mapOf(
                "campflare-campgrounds" to
                    ca.floo.roadtrip.service.etl.vendors.campflare
                        .CampflareCampgroundsEtl(),
                "campflare-campsites" to
                    ca.floo.roadtrip.service.etl.vendors.campflare
                        .CampflareCampsitesEtl(),
                "bcparks-strapi" to
                    ca.floo.roadtrip.service.etl.vendors.bcparks
                        .BcParksStrapiEtl(),
                "federal-campgrounds" to
                    ca.floo.roadtrip.service.etl.vendors.recgov
                        .RecGovCampgroundsEtl("federal-campgrounds"),
                "aspira-leaves-wa" to
                    ca.floo.roadtrip.service.etl.vendors.aspira
                        .AspiraLeavesEtl("aspira-leaves-wa"),
                "aspira-wa-pins" to
                    ca.floo.roadtrip.service.etl.vendors.aspira
                        .AspiraJoinByNameEtl("aspira-wa-pins"),
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
                "new-york-state-parks" to
                    ca.floo.roadtrip.service.etl.vendors.reserveamerica
                        .ReserveAmericaEtl("new-york-state-parks"),
                "california-state-parks" to
                    ca.floo.roadtrip.service.etl.vendors.reservecalifornia
                        .ReserveCaliforniaEtl("california-state-parks"),
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
                "california-state-park-sites" to
                    ca.floo.roadtrip.service.etl.vendors.reservecalifornia
                        .ReserveCaliforniaSitesEtl("california-state-park-sites"),
                "alberta-provincial-park-sites" to
                    ca.floo.roadtrip.service.etl.vendors.reserveamerica
                        .ReserveAmericaSitesEtl("alberta-provincial-park-sites", "ABPP"),
                "new-york-state-park-sites" to
                    ca.floo.roadtrip.service.etl.vendors.reserveamerica
                        .ReserveAmericaSitesEtl("new-york-state-park-sites", "NY"),
                "planet-fitness" to
                    ca.floo.roadtrip.service.etl.vendors.osmpf
                        .PlanetFitnessEtl(),
                "tesla-superchargers" to
                    ca.floo.roadtrip.service.etl.vendors.tesla
                        .TeslaIndexEtl(),
            )

        // Runnable joiner adapters keyed by YAML `adapter:` name. Kept
        // separate from `registry` because joiners don't emit typed ETL
        // output. They select vendor-scoped parent links through the
        // campsite-parent joiner repo; runJoiner owns sequencing and retry.
        val joinerRegistry: Map<String, CampsiteParentJoiner> =
            listOf(
                RecgovCampsiteParentJoiner(),
                AspiraCampsiteParentJoiner(),
                ReserveAmericaCampsiteParentJoiner(),
                ReserveCaliforniaCampsiteParentJoiner(),
            ).associateBy { it.adapter }
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
    runCatching {
        kotlinx.serialization.serializer(value::class.java) as kotlinx.serialization.KSerializer<Any>
    }.getOrElse { cause ->
        throw IllegalStateException(
            "intermediate ETL output type ${value::class.qualifiedName} has no kotlinx.serialization serializer; " +
                "add @Serializable to the OUT class or provide an explicit serializer",
            cause,
        )
    }
