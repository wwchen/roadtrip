package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.domain.CatalogUpsertResult
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.TransformResult
import ca.floo.roadtrip.model.metadata.registry.EtlEntry
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.ImportRunRepo
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
//   4. Buffer terminal upsert candidates and flush bounded batches through
//      the terminal ETL binding's sink.
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
     * Terminal ETL binding map keyed by YAML slug. Defaults to the
     * production registry; overridable for tests.
     */
    private val etlRegistry: Map<String, TerminalEtlBinding<*, *>> = productionEtlRegistry(ctx),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val importRunRepo = ImportRunRepo(ctx)
    private val rawCaptureStore = RawCaptureStore(rawDir = rawDir, staticDir = staticDir)

    /**
     * Per-row run summary for import rows. `poi_data` and `campsite_data`
     * both flow through the same terminal runner; candidate persistence is
     * selected by the terminal binding.
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
        val binding =
            etlRegistry[entry.slug]
                ?: error("no adapter registered for etl slug='${entry.slug}'")
        log.info(
            "  terminal slug={} adapter={}",
            entry.slug,
            binding.adapterName,
        )
        return runTerminal(rowName, sectionLabel, binding, buildBundle(entry.inputs), transformCtx)
    }

    @Suppress("UNCHECKED_CAST")
    private fun runTerminal(
        rowName: String,
        sectionLabel: String,
        binding: TerminalEtlBinding<*, *>,
        bundle: InputBundle,
        transformCtx: TransformCtx,
    ): Stats {
        val concrete = binding as TerminalEtlBinding<Any?, Any?>
        val etl = concrete.etl
        val runId = importRunRepo.start(concrete.etlSlug)
        val batcher = concrete.accumulator()
        var parseOk = 0
        var parseBad = 0
        var transformOk = 0
        var transformBad = 0
        var upserted = 0
        var repoSkipped = 0

        try {
            for (parseResult in etl.parse(bundle)) {
                when (parseResult) {
                    is ParseResult.Ok -> {
                        parseOk++
                        for (transformResult in etl.transform(parseResult.dto, transformCtx)) {
                            when (transformResult) {
                                is TransformResult.Ok -> {
                                    transformOk++
                                    val counts = batcher.add(transformResult.record)
                                    upserted += counts.upserted
                                    repoSkipped += counts.skipped
                                }
                                is TransformResult.Bad -> {
                                    transformBad++
                                    logRowError(
                                        sectionLabel = sectionLabel,
                                        rowName = rowName,
                                        etlSlug = etl.etlSlug,
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
                            etlSlug = etl.etlSlug,
                            stage = "parse",
                            sourceId = parseResult.sourceId,
                            errors = parseResult.errors,
                            count = parseBad,
                        )
                    }
                }
            }

            val finalCounts = batcher.flush()
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
                etl.etlSlug,
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
                    etl.etlSlug,
                    skipped,
                    parseBad,
                    transformBad,
                    repoSkipped,
                )
            }
            return Stats(
                poiDataName = rowName,
                terminalEtlSlug = etl.etlSlug,
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

        internal val registry: Map<String, TerminalEtlDefinition<*, *>> = productionTerminalEtlDefinitions
    }
}
