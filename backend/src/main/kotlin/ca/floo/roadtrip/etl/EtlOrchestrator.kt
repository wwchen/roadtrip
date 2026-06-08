package ca.floo.roadtrip.etl

import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.io.File

// Orchestrates one ETL run: parse → validate → transform → upsert.
// Per-source ETLs implement SourceEtl; this class drives them.
//
// PR 3 ships only PlanetFitnessEtl wired through; subsequent PRs add
// per-source ETLs by registering them here.
class EtlOrchestrator(
    private val ctx: DSLContext,
    private val rawDir: File,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val upsert = Upsert(ctx)

    data class Stats(
        val source: String,
        val parsed: Int,
        val validationErrors: Int,
        val transformed: Int,
        val upsertResult: Upsert.Result,
    )

    /**
     * Run a single source's ETL pipeline against the newest capture in
     * `data/raw/<source>/`. Throws if the source isn't registered or if
     * the upsert tripwire fires. Callers that want a no-op behavior for
     * missing adapters should pre-check `source in registry`.
     */
    fun runSource(source: String): Stats {
        val etl = registry[source] ?: error("no ETL registered for source=$source")
        log.info("etl source={} starting", source)

        val transformCtx = TransformCtx.load(ctx, rawDir)
        val pois = runOneSource(etl, transformCtx)
        val ups = upsert.run(setOf(source), pois)

        log.info(
            "etl source={} done: transformed={} upserted={} swept={}",
            source,
            pois.size,
            ups.seenCount,
            ups.sweptCount,
        )
        return Stats(
            source = source,
            parsed = pois.size, // PR 3 wedge: we only see post-transform count; a richer Stats lands in PR 3b.
            validationErrors = 0,
            transformed = pois.size,
            upsertResult = ups,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <DTO, OUT : Poi> runOneSource(
        etl: SourceEtl<DTO, OUT>,
        transformCtx: TransformCtx,
    ): List<OUT> {
        val dto =
            if (etl.multiPart) {
                etl.parseMulti(RawCapture.newestMultiPart(rawDir, etl.sourceName))
            } else {
                etl.parse(RawCapture.newestSingle(rawDir, etl.sourceName))
            }
        val validated =
            when (val v = etl.validate(dto)) {
                is ValidationResult.Ok -> v.dto
                is ValidationResult.Bad -> {
                    log.warn("etl source={} validation failed: {}", etl.sourceName, v.errors)
                    return emptyList()
                }
            }
        return etl.transform(validated, transformCtx)
    }

    companion object {
        // Source name → ETL impl. Adding a new source = appending one line.
        // PR 3 wedge: PlanetFitness only.
        val registry: Map<String, SourceEtl<*, *>> =
            mapOf(
                "osm-pf" to
                    ca.floo.roadtrip.etl.osmpf
                        .PlanetFitnessEtl(),
                "padus-national-parks" to
                    ca.floo.roadtrip.etl.padus
                        .PadusNpEtl(),
                "padus-state-parks" to
                    ca.floo.roadtrip.etl.padus
                        .PadusSpEtl(),
                "bcparks-strapi" to
                    ca.floo.roadtrip.etl.bcparks
                        .BcParksStrapiEtl(),
                "tesla-index" to
                    ca.floo.roadtrip.etl.tesla
                        .TeslaIndexEtl(),
                "reserveamerica-abpp" to
                    ca.floo.roadtrip.etl.reserveamerica
                        .ReserveAmericaEtl(),
            )
    }
}
