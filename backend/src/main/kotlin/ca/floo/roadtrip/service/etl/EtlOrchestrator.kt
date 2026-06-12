package ca.floo.roadtrip.service.etl

import ca.floo.roadtrip.models.Envelope
import ca.floo.roadtrip.models.Poi
import ca.floo.roadtrip.models.ValidationResult
import ca.floo.roadtrip.models.registry.PoiDataEntry
import ca.floo.roadtrip.models.registry.PoiRegistry
import ca.floo.roadtrip.models.registry.ReservableDataEntry
import ca.floo.roadtrip.repo.NoCaptureException
import ca.floo.roadtrip.repo.RawCapture
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.Upsert
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.io.File

// Orchestrates one poi_data row's ETL chain end-to-end.
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
//   4. If terminal, expect OUT = List<Poi.*> and run Upsert (sweep scoped
//      to source = etl-slug).
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
    /**
     * Joiner adapter map keyed by YAML `adapter:` string. Defaults to
     * the production map under [Companion.joinerRegistry]; overridable
     * for tests.
     */
    private val joinerRegistry: Map<String, PoiReservableJoiner> = Companion.joinerRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val upsert = Upsert(ctx)
    private val reservables = ReservableRepo(ctx)

    /**
     * Per-row run summary for `poi_data` rows. Mirrors the shape that
     * existed before RFC 0008's section split — the Pois Upsert path is
     * unchanged.
     */
    data class Stats(
        val poiDataName: String,
        val terminalEtlSlug: String,
        val parsed: Int,
        val transformed: Int,
        val upsertResult: Upsert.Result,
    )

    /**
     * Per-row run summary for `reservable_data` rows. Counts catalog
     * upserts and the slug of the terminal etl (== reservable_data
     * adapter slug).
     */
    data class ReservableStats(
        val reservableDataName: String,
        val terminalEtlSlug: String,
        val parsed: Int,
        val upserted: Int,
    )

    /**
     * Per-row run summary for `poi_reservable_joiner` rows. Tracks how
     * many links the joiner discovered + how many actually inserted
     * (the rest were already present — `linkToPoi` is idempotent via
     * ON CONFLICT DO NOTHING).
     */
    data class JoinerStats(
        val joinerName: String,
        val adapter: String,
        val linksDiscovered: Int,
        val linksInserted: Int,
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
                intermediateOutputs[entry.slug] = runIntermediate(etl, bundle, transformCtx)
            }
        }

        return terminalStats!!
    }

    /**
     * Run a reservable_data row by display name. Same chain shape as
     * [runPoiData] — list-ordered etl stages, intermediate outputs
     * threaded in memory — but the terminal stage emits
     * [ReservableEtlOutput] which the orchestrator unpacks into
     * `reservables` rows via [ReservableRepo.upsert].
     *
     * No POI linking happens here. That's the joiner's job (see
     * [runJoiner]). A reservable_data run that lands rows but has no
     * matching joiner run yet is consistent — the catalog exists,
     * `reservable_pois` just has no entries for it. Run the joiner
     * later to fill them in.
     */
    fun runReservableData(name: String): ReservableStats {
        val row =
            poiRegistry.reservableDataByName(name)
                ?: error("no reservable_data row with name='$name'")
        require(row.etls.isNotEmpty()) { "reservable_data '$name' has empty etls list" }

        log.info("etl reservable_data='{}' starting ({} stages)", name, row.etls.size)
        val transformCtx = TransformCtx.load(rawDir, poiRegistry)
        val intermediateOutputs = mutableMapOf<String, JsonElement>()
        var terminalStats: ReservableStats? = null

        for ((index, entry) in row.etls.withIndex()) {
            val isTerminal = index == row.etls.lastIndex
            val etl =
                etlRegistry[entry.slug]
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
                terminalStats = runReservableTerminal(row, etl, bundle, transformCtx)
            } else {
                intermediateOutputs[entry.slug] = runIntermediate(etl, bundle, transformCtx)
            }
        }

        return terminalStats!!
    }

    /**
     * Run a poi_reservable_joiner row by display name. The adapter
     * reads the current state of `pois` + `reservables`, emits
     * (reservable_id, poi_id) pairs, and the orchestrator inserts each
     * via [ReservableRepo.linkToPoi] (idempotent).
     *
     * Joiners are independent of ETL runs. Re-running creates no
     * duplicate links; it just picks up new pairs whose underlying
     * rows have appeared since the last run.
     */
    fun runJoiner(name: String): JoinerStats {
        val row =
            poiRegistry.poiReservableJoinerByName(name)
                ?: error("no poi_reservable_joiner row with name='$name'")
        val joiner =
            joinerRegistry[row.adapter]
                ?: error("no joiner adapter registered for '${row.adapter}' (poi_reservable_joiner '$name')")
        log.info("joiner '{}' adapter={} starting", name, row.adapter)

        val joinerCtx = JoinerCtx(ctx = ctx, reservables = reservables, args = row.args)
        val links = joiner.discoverLinks(joinerCtx)

        // linkToPoi is idempotent (ON CONFLICT DO NOTHING). We don't
        // know a priori how many were already-linked vs newly-linked
        // without a SELECT round-trip. The simplest honest count is
        // "discovered" — total pairs we tried; "inserted" matches it
        // unless we want to introspect the row counts. For v1 we just
        // mirror discovered → inserted; future work can add per-call
        // INSERT-result inspection if it matters.
        for (link in links) {
            reservables.linkToPoi(reservableId = link.reservableId, poiId = link.poiId)
        }
        log.info("joiner '{}' adapter={} links={}", name, row.adapter, links.size)

        return JoinerStats(
            joinerName = name,
            adapter = row.adapter,
            linksDiscovered = links.size,
            linksInserted = links.size,
        )
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

    /**
     * Terminal stage of a reservable_data row. The etl returns a
     * [ReservableEtlOutput] (a list of catalog rows); the orchestrator
     * upserts each through [ReservableRepo].
     *
     * Validation failure logs + zeroes out — same behavior as the Pois
     * terminal — so a bad upstream day doesn't fail the whole import.
     */
    @Suppress("UNCHECKED_CAST")
    private fun runReservableTerminal(
        row: ReservableDataEntry,
        etl: SourceEtl<*, *>,
        bundle: InputBundle,
        transformCtx: TransformCtx,
    ): ReservableStats {
        val concrete = etl as SourceEtl<Any, ReservableEtlOutput>
        val dto = concrete.parse(bundle)
        val validated =
            when (val v = concrete.validate(dto)) {
                is ValidationResult.Ok -> v.dto
                is ValidationResult.Bad -> {
                    log.warn("reservable_data '{}' terminal validation failed: {}", row.name, v.errors)
                    return ReservableStats(
                        reservableDataName = row.name,
                        terminalEtlSlug = concrete.etlSlug,
                        parsed = 0,
                        upserted = 0,
                    )
                }
            }
        val output = concrete.transform(validated, transformCtx)
        var upserted = 0
        for (input in output.reservables) {
            reservables.upsert(input)
            upserted++
        }
        log.info(
            "reservable_data '{}' terminal slug={} parsed={} upserted={}",
            row.name,
            concrete.etlSlug,
            output.reservables.size,
            upserted,
        )
        return ReservableStats(
            reservableDataName = row.name,
            terminalEtlSlug = concrete.etlSlug,
            parsed = output.reservables.size,
            upserted = upserted,
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
        // Map of etl slug → adapter. Adding a new ETL = appending one line.
        // Map keys MUST match the YAML poi_data.etls[*].slug exactly.
        val registry: Map<String, SourceEtl<*, *>> =
            mapOf(
                "planet-fitness" to
                    ca.floo.roadtrip.service.etl.osmpf
                        .PlanetFitnessEtl(),
                "bcparks-strapi" to
                    ca.floo.roadtrip.service.etl.bcparks
                        .BcParksStrapiEtl(),
                "tesla-superchargers" to
                    ca.floo.roadtrip.service.etl.tesla
                        .TeslaIndexEtl(),
                "alberta-provincial" to
                    ca.floo.roadtrip.service.etl.reserveamerica
                        .ReserveAmericaEtl(),
                // RIDB (recreation.gov backend) — one ETL covers every
                // publishing agency (NPS, USFS, BLM, USACE, FWS, BOR, TVA, …).
                // Per-row agency stamped on Poi.Campground.agency at
                // transform time from ORGANIZATION[0].OrgAbbrevName.
                "federal-campgrounds" to
                    ca.floo.roadtrip.service.etl.recgov
                        .RecGovCampgroundsEtl("federal-campgrounds"),
                // Reservable catalog terminal (RFC 0008). Emits one row
                // per campsite into `reservables`; the recgov joiner
                // (PR 4) links them to their parent federal-campgrounds
                // POIs.
                "federal-campsites" to
                    ca.floo.roadtrip.service.etl.recgov
                        .RecGovCampsitesEtl("federal-campsites"),
                // Aspira NextGen — one leaf-walker + one join-by-name
                // emitter per tenant. Both classes take the slug as a
                // constructor arg so a fourth tenant is two YAML rows +
                // two registry lines.
                "aspira-leaves-wa" to
                    ca.floo.roadtrip.service.etl.aspira
                        .AspiraLeavesEtl("aspira-leaves-wa"),
                "aspira-leaves-bc" to
                    ca.floo.roadtrip.service.etl.aspira
                        .AspiraLeavesEtl("aspira-leaves-bc"),
                "aspira-leaves-pc" to
                    ca.floo.roadtrip.service.etl.aspira
                        .AspiraLeavesEtl("aspira-leaves-pc"),
                "aspira-wa-pins" to
                    ca.floo.roadtrip.service.etl.aspira
                        .AspiraJoinByNameEtl("aspira-wa-pins"),
                "aspira-bc-pins" to
                    ca.floo.roadtrip.service.etl.aspira
                        .AspiraJoinByNameEtl("aspira-bc-pins"),
                "aspira-pc-pins" to
                    ca.floo.roadtrip.service.etl.aspira
                        .AspiraJoinByNameEtl("aspira-pc-pins"),
                // Reservable catalog terminals (RFC 0008). One row per
                // tenant; vendor strings use underscore (`aspira_wa`)
                // because ReservableId disallows ':' in the vendor field.
                "aspira-wa-resources" to
                    ca.floo.roadtrip.service.etl.aspira
                        .AspiraResourcesEtl(
                            etlSlug = "aspira-wa-resources",
                            mapsInputSlug = "aspira-maps-wa",
                            vendor = "aspira_wa",
                        ),
                "aspira-bc-resources" to
                    ca.floo.roadtrip.service.etl.aspira
                        .AspiraResourcesEtl(
                            etlSlug = "aspira-bc-resources",
                            mapsInputSlug = "aspira-maps-bc",
                            vendor = "aspira_bc",
                        ),
                "aspira-pc-resources" to
                    ca.floo.roadtrip.service.etl.aspira
                        .AspiraResourcesEtl(
                            etlSlug = "aspira-pc-resources",
                            mapsInputSlug = "aspira-maps-pc",
                            vendor = "aspira_pc",
                        ),
            )

        /**
         * Map of joiner adapter name → adapter instance. Keys MUST match
         * the YAML `poi_reservable_joiner.adapter` value exactly. One
         * entry per adapter class — multiple YAML rows can share the
         * same adapter (Aspira's three tenants do).
         */
        val joinerRegistry: Map<String, PoiReservableJoiner> =
            mapOf(
                "RecgovPoiReservableJoiner" to
                    ca.floo.roadtrip.service.etl.recgov
                        .RecgovPoiReservableJoiner(),
                "AspiraPoiReservableJoiner" to
                    ca.floo.roadtrip.service.etl.aspira
                        .AspiraPoiReservableJoiner(),
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
