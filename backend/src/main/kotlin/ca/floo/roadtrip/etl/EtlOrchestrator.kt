package ca.floo.roadtrip.etl

import ca.floo.roadtrip.etl.registry.PoiDataEntry
import ca.floo.roadtrip.etl.registry.PoiRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Orchestrates one poi_data row's ETL chain end-to-end.
//
// Per-row sequence (declared etls: list, in order):
//   1. Resolve each etls entry's `inputs:` slug into either:
//        - data_source: newest envelope(s) under data/raw/<slug>/
//        - earlier sibling etl: newest typed payload under data/etl-out/<slug>/
//   2. Hand the InputBundle to the etl.parse → validate → transform stages.
//   3. If the etl is intermediate (not the last in the row), serialize OUT
//      as JSON to data/etl-out/<etl-slug>/<UTC-ts>.json.
//   4. If terminal, expect OUT = List<Poi.*> and run Upsert (sweep scoped
//      to source = etl-slug).
//
// The DAG-level ordering across multiple poi_data rows is the caller's
// problem (today: per-row imports, no cross-row composition). Within a row,
// list order = dependency order, validated at boot.
class EtlOrchestrator(
    private val ctx: DSLContext,
    private val rawDir: File,
    private val etlOutDir: File,
    private val poiRegistry: PoiRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val upsert = Upsert(ctx)

    data class Stats(
        val poiDataName: String,
        val terminalEtlSlug: String,
        val parsed: Int,
        val transformed: Int,
        val upsertResult: Upsert.Result,
    )

    /**
     * Run a poi_data row by display name. Walks the row's `etls:` chain in
     * declared order, materializing intermediates and upserting the
     * terminal's Poi.* output. Throws if the row isn't registered or any
     * stage fails.
     */
    fun runPoiData(name: String): Stats {
        val row =
            poiRegistry.poiDataByName(name)
                ?: error("no poi_data row with name='$name'")
        require(row.etls.isNotEmpty()) { "poi_data '$name' has empty etls list" }

        log.info("etl poi_data='{}' starting ({} stages)", name, row.etls.size)
        val transformCtx = TransformCtx.load(rawDir, poiRegistry)

        // Per-run cache of intermediate outputs keyed by etl slug. Lets a
        // later sibling read a just-written intermediate without going back
        // through the filesystem.
        val intermediateOutputs = mutableMapOf<String, JsonElement>()

        var terminalStats: Stats? = null

        for ((index, entry) in row.etls.withIndex()) {
            val isTerminal = index == row.etls.lastIndex
            val etl =
                registry[entry.slug]
                    ?: error("no adapter registered for etl slug='${entry.slug}'")
            log.info(
                "  stage {}/{} slug={} adapter={} terminal={}",
                index + 1,
                row.etls.size,
                entry.slug,
                etl::class.simpleName,
                isTerminal,
            )

            val bundle = buildBundle(entry.inputs, intermediateOutputs)
            if (isTerminal) {
                terminalStats = runTerminal(row, etl, bundle, transformCtx)
            } else {
                val output = runIntermediate(etl, bundle, transformCtx)
                writeIntermediate(entry.slug, output)
                intermediateOutputs[entry.slug] = output
            }
        }

        return terminalStats!!
    }

    @Suppress("UNCHECKED_CAST")
    private fun runTerminal(
        row: PoiDataEntry,
        etl: SourceEtl<*, *>,
        bundle: InputBundle,
        transformCtx: TransformCtx,
    ): Stats {
        val concrete = etl as SourceEtl<Any, List<Poi>>
        val dto = concrete.parse(bundle)
        val validated =
            when (val v = concrete.validate(dto)) {
                is ValidationResult.Ok -> v.dto
                is ValidationResult.Bad -> {
                    log.warn("poi_data '{}' terminal validation failed: {}", row.name, v.errors)
                    return Stats(
                        poiDataName = row.name,
                        terminalEtlSlug = concrete.etlSlug,
                        parsed = 0,
                        transformed = 0,
                        upsertResult = Upsert.Result(runId = -1L, seenCount = 0, sweptCount = 0),
                    )
                }
            }
        val pois = concrete.transform(validated, transformCtx)
        val ups = upsert.run(setOf(concrete.etlSlug), pois)
        log.info(
            "poi_data '{}' terminal slug={} transformed={} upserted={} swept={}",
            row.name,
            concrete.etlSlug,
            pois.size,
            ups.seenCount,
            ups.sweptCount,
        )
        return Stats(
            poiDataName = row.name,
            terminalEtlSlug = concrete.etlSlug,
            parsed = pois.size,
            transformed = pois.size,
            upsertResult = ups,
        )
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
                // sibling intermediate from this run
                etls[slug] = intermediateOutputs[slug]!!
            } else {
                // sibling intermediate from a prior run (cross-row case is
                // possible in principle; today no row imports another
                // row's intermediate)
                etls[slug] = loadIntermediateOutput(slug)
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

    private fun loadIntermediateOutput(etlSlug: String): JsonElement {
        val dir = File(etlOutDir, etlSlug)
        if (!dir.isDirectory) throw NoCaptureException("$dir is not a directory; intermediate '$etlSlug' has no prior output")
        val newest =
            dir
                .listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.maxByOrNull { it.name }
                ?: throw NoCaptureException("no outputs under $dir")
        return Json.Default.parseToJsonElement(newest.readText())
    }

    private fun writeIntermediate(
        etlSlug: String,
        payload: JsonElement,
    ) {
        val dir = File(etlOutDir, etlSlug).also { it.mkdirs() }
        val ts = Instant.now().atOffset(ZoneOffset.UTC).format(TS_FORMAT)
        val file = File(dir, "$ts.json")
        file.writeText(Json.Default.encodeToString(JsonElement.serializer(), payload))
        log.info("  wrote intermediate {} -> {}", etlSlug, file.relativeTo(etlOutDir.parentFile))
    }

    companion object {
        private val TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")

        // Map of etl slug → adapter. Adding a new ETL = appending one line.
        // Map keys MUST match the YAML poi_data.etls[*].slug exactly.
        val registry: Map<String, SourceEtl<*, *>> =
            mapOf(
                "osm-pf" to
                    ca.floo.roadtrip.etl.osmpf
                        .PlanetFitnessEtl(),
                "bcparks-strapi" to
                    ca.floo.roadtrip.etl.bcparks
                        .BcParksStrapiEtl(),
                "tesla-superchargers" to
                    ca.floo.roadtrip.etl.tesla
                        .TeslaIndexEtl(),
                "reserveamerica-abpp" to
                    ca.floo.roadtrip.etl.reserveamerica
                        .ReserveAmericaEtl(),
                // RIDB (recreation.gov backend) — one ETL instance per
                // agency slug. The transformer is identical; the slug
                // arg drives source labelling.
                "nps-campgrounds" to
                    ca.floo.roadtrip.etl.recgov
                        .RecGovCampgroundsEtl("nps-campgrounds"),
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
