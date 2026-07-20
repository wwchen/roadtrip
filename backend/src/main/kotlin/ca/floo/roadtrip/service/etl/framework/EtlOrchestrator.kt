package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.model.domain.CatalogUpsertResult
import ca.floo.roadtrip.model.domain.PlanetFitnessLocationUpsertCandidate
import ca.floo.roadtrip.model.domain.TeslaSuperchargerUpsertCandidate
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.TransformResult
import ca.floo.roadtrip.model.metadata.registry.EtlEntry
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.ImportRunRepo
import ca.floo.roadtrip.repo.MAX_CATALOG_UPSERT_BATCH_SIZE
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.io.File

// Orchestrates one poi_data/campsite_data row's terminal ETL end-to-end.
//
// Per-row sequence:
//   1. Resolve the single etls entry's `inputs:` slugs to newest data_source
//      envelope(s) under each registry output_dir_prefix.
//   2. Consume etl.parse results, counting ParseResult.Bad without failing
//      the import run.
//   3. Consume etl.transform results, counting TransformResult.Bad without
//      failing the import run.
//   4. Buffer terminal upsert candidates by catalog entity and flush bounded
//      batches through the owning entity repo.
//
// Raw capture ordering across data_sources remains the caller's problem.
// Registry validation rejects intermediate ETL chains, so one import job is
// one terminal ETL run over one snapshot of raw input.
open class EtlOrchestrator(
    private val ctx: DSLContext,
    private val rawDir: File,
    private val poiRegistry: PoiRegistry,
    /**
     * Base directory for registry paths such as data_source output_dir_prefix.
     */
    private val staticDir: File,
    /**
     * ETL adapter map keyed by YAML slug. Defaults to the production
     * registry under [Companion.registry]; overridable for tests.
     */
    private val etlRegistry: Map<String, SourceEtl<*, *>> = registry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val campgroundRepo = CampgroundRepo(ctx)
    private val campsiteRepo = CampsiteRepo(ctx)
    private val teslaSuperchargerRepo = TeslaSuperchargerRepo(ctx)
    private val planetFitnessLocationRepo = PlanetFitnessLocationRepo(ctx)
    private val importRunRepo = ImportRunRepo(ctx)
    private val rawCaptureStore = RawCaptureStore(rawDir = rawDir, staticDir = staticDir)

    /**
     * Per-row run summary for import rows. `poi_data` and `campsite_data`
     * both flow through the same terminal runner; candidate persistence is
     * selected by the emitted record type.
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

    /**
     * Run a poi_data row by display name. Throws if the row isn't registered
     * or its terminal stage fails.
     */
    fun runPoiData(name: String): Stats {
        val row =
            poiRegistry.poiDataByName(name)
                ?: error("no poi_data row with name='$name'")
        require(row.etls.size == 1) { "poi_data '$name' must declare exactly one etl" }

        log.info("etl poi_data='{}' starting slug={}", name, row.etls.single().slug)
        return runEtlJob(
            rowName = row.name,
            sectionLabel = "poi_data",
            entry = row.etls.single(),
        )
    }

    fun runCampsiteData(name: String): CampsiteStats {
        val row =
            poiRegistry.campsiteDataByName(name)
                ?: error("no campsite_data row with name='$name'")
        require(row.etls.size == 1) { "campsite_data '$name' must declare exactly one etl" }

        log.info("etl campsite_data='{}' starting slug={}", name, row.etls.single().slug)
        val stats =
            runEtlJob(
                rowName = row.name,
                sectionLabel = "campsite_data",
                entry = row.etls.single(),
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

    private fun runEtlJob(
        rowName: String,
        sectionLabel: String,
        entry: EtlEntry,
    ): Stats {
        val transformCtx = TransformCtx.load(rawDir, poiRegistry)
        val etl =
            etlRegistry[entry.slug]
                ?: error("no adapter registered for etl slug='${entry.slug}'")
        log.info(
            "  terminal slug={} adapter={}",
            entry.slug,
            etl::class.simpleName,
        )
        return runTerminal(rowName, sectionLabel, etl, buildBundle(entry.inputs), transformCtx)
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
        val runId = importRunRepo.start(concrete.etlSlug)
        val buffers = UpsertBuffers()
        var parseOk = 0
        var parseBad = 0
        var transformOk = 0
        var transformBad = 0
        var upserted = 0
        var repoSkipped = 0

        try {
            for (parseResult in concrete.parse(bundle)) {
                when (parseResult) {
                    is ParseResult.Ok -> {
                        parseOk++
                        for (transformResult in concrete.transform(parseResult.dto, transformCtx)) {
                            when (transformResult) {
                                is TransformResult.Ok -> {
                                    transformOk++
                                    val counts = buffers.add(transformResult.record)
                                    upserted += counts.upserted
                                    repoSkipped += counts.skipped
                                }
                                is TransformResult.Bad -> {
                                    transformBad++
                                    logRowError(
                                        sectionLabel = sectionLabel,
                                        rowName = rowName,
                                        etlSlug = concrete.etlSlug,
                                        stage = "transform",
                                        sourceId = transformResult.sourceId,
                                        errors = transformResult.errors,
                                        count = transformBad,
                                    )
                                }
                            }
                        }
                    }
                    is ParseResult.Bad -> {
                        parseBad++
                        logRowError(
                            sectionLabel = sectionLabel,
                            rowName = rowName,
                            etlSlug = concrete.etlSlug,
                            stage = "parse",
                            sourceId = parseResult.sourceId,
                            errors = parseResult.errors,
                            count = parseBad,
                        )
                    }
                }
            }

            val finalCounts = buffers.flushAll()
            upserted += finalCounts.upserted
            repoSkipped += finalCounts.skipped

            val seen = transformOk + parseBad + transformBad
            val skipped = parseBad + transformBad + repoSkipped
            importRunRepo.complete(runId, seen)
            val ups =
                CatalogUpsertResult(
                    runId = runId,
                    seenCount = seen,
                    upsertedCount = upserted,
                    skippedCount = skipped,
                )

            log.info(
                "{} '{}' terminal slug={} parseOk={} parseBad={} transformOk={} transformBad={} upserted={} repoSkipped={}",
                sectionLabel,
                rowName,
                concrete.etlSlug,
                parseOk,
                parseBad,
                transformOk,
                transformBad,
                upserted,
                repoSkipped,
            )
            if (skipped > 0) {
                log.warn(
                    "{} '{}' terminal slug={} skipped {} records (parseBad={} transformBad={} repoSkipped={})",
                    sectionLabel,
                    rowName,
                    concrete.etlSlug,
                    skipped,
                    parseBad,
                    transformBad,
                    repoSkipped,
                )
            }
            return Stats(
                poiDataName = rowName,
                terminalEtlSlug = concrete.etlSlug,
                parsed = seen,
                transformed = transformOk,
                upsertResult = ups,
            )
        } catch (e: Throwable) {
            importRunRepo.fail(runId, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun logRowError(
        sectionLabel: String,
        rowName: String,
        etlSlug: String,
        stage: String,
        sourceId: String?,
        errors: List<String>,
        count: Int,
    ) {
        if (count <= ROW_ERROR_LOG_SAMPLE_LIMIT) {
            log.warn(
                "{} '{}' terminal slug={} {} bad sourceId={} errors={}",
                sectionLabel,
                rowName,
                etlSlug,
                stage,
                sourceId,
                errors,
            )
        }
    }

    private inner class UpsertBuffers {
        private val campgrounds = mutableListOf<CampgroundUpsertCandidate>()
        private val campsites = mutableListOf<CampsiteUpsertCandidate>()
        private val teslaSuperchargers = mutableListOf<TeslaSuperchargerUpsertCandidate>()
        private val planetFitnessLocations = mutableListOf<PlanetFitnessLocationUpsertCandidate>()

        fun add(record: Any): FlushCounts {
            when (record) {
                is CampgroundUpsertCandidate -> {
                    campgrounds += record
                    if (campgrounds.size >= MAX_CATALOG_UPSERT_BATCH_SIZE) return flushCampgrounds()
                }
                is CampsiteUpsertCandidate -> {
                    campsites += record
                    if (campsites.size >= MAX_CATALOG_UPSERT_BATCH_SIZE) return flushCampsites()
                }
                is TeslaSuperchargerUpsertCandidate -> {
                    teslaSuperchargers += record
                    if (teslaSuperchargers.size >= MAX_CATALOG_UPSERT_BATCH_SIZE) return flushTeslaSuperchargers()
                }
                is PlanetFitnessLocationUpsertCandidate -> {
                    planetFitnessLocations += record
                    if (planetFitnessLocations.size >= MAX_CATALOG_UPSERT_BATCH_SIZE) return flushPlanetFitnessLocations()
                }
                else -> error("unsupported terminal output record ${record::class.qualifiedName}")
            }
            return FlushCounts()
        }

        fun flushAll(): FlushCounts = flushCampgrounds() + flushCampsites() + flushTeslaSuperchargers() + flushPlanetFitnessLocations()

        private fun flushCampgrounds(): FlushCounts {
            if (campgrounds.isEmpty()) return FlushCounts()
            val batch = campgrounds.toList()
            campgrounds.clear()
            return FlushCounts(upserted = campgroundRepo.upsertCampgroundBatch(batch))
        }

        private fun flushCampsites(): FlushCounts {
            if (campsites.isEmpty()) return FlushCounts()
            val batch = campsites.toList()
            campsites.clear()
            val (upserted, skipped) = campsiteRepo.upsertCampsiteBatch(batch)
            return FlushCounts(upserted = upserted, skipped = skipped)
        }

        private fun flushTeslaSuperchargers(): FlushCounts {
            if (teslaSuperchargers.isEmpty()) return FlushCounts()
            val batch = teslaSuperchargers.toList()
            teslaSuperchargers.clear()
            return FlushCounts(upserted = teslaSuperchargerRepo.upsertTeslaSuperchargerBatch(batch))
        }

        private fun flushPlanetFitnessLocations(): FlushCounts {
            if (planetFitnessLocations.isEmpty()) return FlushCounts()
            val batch = planetFitnessLocations.toList()
            planetFitnessLocations.clear()
            return FlushCounts(upserted = planetFitnessLocationRepo.upsertPlanetFitnessLocationBatch(batch))
        }
    }

    private data class FlushCounts(
        val upserted: Int = 0,
        val skipped: Int = 0,
    )

    private operator fun FlushCounts.plus(other: FlushCounts): FlushCounts =
        FlushCounts(
            upserted = upserted + other.upserted,
            skipped = skipped + other.skipped,
        )

    private fun buildBundle(inputSlugs: List<String>): InputBundle {
        val raw = LinkedHashMap<String, List<Envelope>>()
        for (slug in inputSlugs) {
            val ds = poiRegistry.dataSource(slug)
            if (ds != null) {
                // data_source input: load envelope(s) from its registry output_dir_prefix.
                raw[slug] = rawCaptureStore.loadNewestEnvelopes(ds)
            } else {
                error("input '$slug' is not a data_source — should have been caught by PoiRegistry.validate()")
            }
        }
        return InputBundle(raw)
    }

    companion object {
        private const val ROW_ERROR_LOG_SAMPLE_LIMIT = 10

        // Runnable ETLs. Admin import targets use this map to decide what can
        // run; every configured campground/campsite vendor row exposed here
        // writes through the canonical catalog repo.
        val registry: Map<String, SourceEtl<*, *>> =
            mapOf(
                // Campflare
                "campflare-campgrounds" to
                    ca.floo.roadtrip.service.etl.vendors.campflare
                        .CampflareCampgroundsEtl(),
                "campflare-campsites" to
                    ca.floo.roadtrip.service.etl.vendors.campflare
                        .CampflareCampsitesEtl(),
                // Rec.gov
                "recgov-campgrounds" to
                    ca.floo.roadtrip.service.etl.vendors.recgov
                        .RecGovCampgroundsEtl("recgov-campgrounds"),
                "recgov-campsites" to
                    ca.floo.roadtrip.service.etl.vendors.recgov
                        .RecGovCampsitesEtl("recgov-campsites"),
                // Aspira WA
                "aspira-wa-campgrounds" to
                    ca.floo.roadtrip.service.etl.vendors.aspira
                        .AspiraCampgroundsEtl("aspira-wa-campgrounds", DataProvider.ASPIRA, "wa"),
                "aspira-wa-campsites" to
                    ca.floo.roadtrip.service.etl.vendors.aspira
                        .AspiraCampsitesEtl(
                            etlSlug = "aspira-wa-campsites",
                            mapsInputSlug = "aspira-maps-wa",
                            inventoryInputSlug = "aspira-inventory-wa",
                            dictionariesInputSlug = "aspira-dictionaries-wa",
                            aspiraTenant = "wa",
                        ),
                // Aspira BC
                "aspira-bc-campgrounds" to
                    ca.floo.roadtrip.service.etl.vendors.bcparks
                        .BcParksCampgroundsEtl(),
                "aspira-bc-campsites" to
                    ca.floo.roadtrip.service.etl.vendors.aspira
                        .AspiraCampsitesEtl(
                            etlSlug = "aspira-bc-campsites",
                            mapsInputSlug = "aspira-maps-bc",
                            inventoryInputSlug = "aspira-inventory-bc",
                            dictionariesInputSlug = "aspira-dictionaries-bc",
                            aspiraTenant = "bc",
                            parentDataProvider = ca.floo.roadtrip.model.domain.provider.DataProvider.STRAPI,
                        ),
                // Aspira PC
                "aspira-pc-campgrounds" to
                    ca.floo.roadtrip.service.etl.vendors.aspira
                        .AspiraCampgroundsEtl("aspira-pc-campgrounds", DataProvider.ASPIRA, "pc"),
                "aspira-pc-campsites" to
                    ca.floo.roadtrip.service.etl.vendors.aspira
                        .AspiraCampsitesEtl(
                            etlSlug = "aspira-pc-campsites",
                            mapsInputSlug = "aspira-maps-pc",
                            inventoryInputSlug = "aspira-inventory-pc",
                            dictionariesInputSlug = "aspira-dictionaries-pc",
                            aspiraTenant = "pc",
                        ),
                // ReserveAmerica AB
                "reserveamerica-ab-campgrounds" to
                    ca.floo.roadtrip.service.etl.vendors.reserveamerica
                        .ReserveAmericaCampgroundsEtl("reserveamerica-ab-campgrounds"),
                "reserveamerica-ab-campsites" to
                    ca.floo.roadtrip.service.etl.vendors.reserveamerica
                        .ReserveAmericaSitesEtl("reserveamerica-ab-campsites", "ABPP"),
                // ReserveAmerica NY
                "reserveamerica-ny-campgrounds" to
                    ca.floo.roadtrip.service.etl.vendors.reserveamerica
                        .ReserveAmericaCampgroundsEtl("reserveamerica-ny-campgrounds"),
                "reserveamerica-ny-campsites" to
                    ca.floo.roadtrip.service.etl.vendors.reserveamerica
                        .ReserveAmericaSitesEtl("reserveamerica-ny-campsites", "NY"),
                // ReserveCalifornia
                "reservecalifornia-campgrounds" to
                    ca.floo.roadtrip.service.etl.vendors.reservecalifornia
                        .ReserveCaliforniaCampgroundsEtl("reservecalifornia-campgrounds"),
                "reservecalifornia-campsites" to
                    ca.floo.roadtrip.service.etl.vendors.reservecalifornia
                        .ReserveCaliforniaSitesEtl("reservecalifornia-campsites"),
                // Planet Fitness
                "planet-fitness" to
                    ca.floo.roadtrip.service.etl.vendors.osmpf
                        .PlanetFitnessEtl(),
                // Tesla
                "tesla-superchargers" to
                    ca.floo.roadtrip.service.etl.vendors.tesla
                        .TeslaIndexEtl(),
            )
    }
}
