package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.TransformResult

// Contract every per-row ETL implements. Stages are pure functions
// (no DB, no IO) — testable against captured raw fixtures.
//
// Inputs: declared in YAML as the etl's `inputs:` list. Each input slug
// resolves to a data_source whose newest envelope(s) get loaded from its
// registry output_dir_prefix. The orchestrator hands the ETL an InputBundle
// keyed by slug.
//
// Outputs: terminal ETLs emit upsert candidates directly; the orchestrator
// batches those records and persists through the owning entity repo.
//
// Per RFC decision #26: one file per ETL under etl/<vendor>/, all stages
// co-located. The interface gives a uniform shape to grep across; the
// per-vendor file keeps the cohesive mass.
interface SourceEtl<DTO, OUT> {
    /**
     * The etl's YAML slug. Must match exactly. Terminal ETLs use this as
     * the catalog import source so the upsert sweep stays scoped per-terminal.
     */
    val etlSlug: String

    /**
     * True if any data_source-typed input writes a directory of `page-NNN.json`
     * files. Default false: each data_source-typed input is one envelope per
     * run.
     */
    val multiPart: Boolean get() = false

    /**
     * Verbatim raw inputs to strongly typed parse results. Fatal missing input
     * or malformed capture errors can still throw; recoverable row/page errors
     * should yield [ParseResult.Bad].
     */
    fun parse(inputs: InputBundle): Sequence<ParseResult<DTO>>

    /**
     * Parsed DTO to terminal upsert candidates. Recoverable row-level errors
     * should yield [TransformResult.Bad] so the orchestrator can count them
     * without failing the whole import run.
     */
    fun transform(
        dto: DTO,
        ctx: TransformCtx,
    ): Sequence<TransformResult<OUT>>
}
