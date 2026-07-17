package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.metadata.ValidationResult

// Contract every per-row ETL implements. Stages are pure functions
// (no DB, no IO) — testable against captured raw fixtures.
//
// Inputs: declared in YAML as the etl's `inputs:` list. Each input slug
// resolves to either a data_source (whose newest envelope(s) get loaded
// from data/raw/<slug>/) or an earlier sibling etl in the same poi_data
// row (whose typed payload gets loaded from data/etl-out/<slug>/). The
// orchestrator hands the ETL an InputBundle keyed by slug.
//
// Outputs: an intermediate ETL returns any @Serializable payload. A terminal
// ETL returns one of the canonical catalog output DTOs; the orchestrator
// persists records through the owning entity repo.
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
     * True if any data_source-typed input writes a directory of
     * `page-NNN.json` files (the orchestrator hands every page to
     * [InputBundle.envelopes]). Default false: each data_source-typed
     * input is one envelope per run.
     *
     * Has no effect on etl-typed inputs — those always materialize as
     * one typed payload from the upstream's newest run.
     */
    val multiPart: Boolean get() = false

    /**
     * Verbatim raw inputs → strongly-typed DTO. Pure deserialization;
     * no transform, no merge.
     */
    fun parse(inputs: InputBundle): DTO

    /**
     * DTO row → ok | errors. Validation rules are per-ETL (required
     * fields, enum membership, geometry well-formedness, ID format).
     * Bad rows are counted but don't fail the run.
     */
    fun validate(dto: DTO): ValidationResult<DTO>

    /**
     * DTO → OUT. Pure except for the read-only lookups TransformCtx
     * provides (subcategory, agency, adapter args). Terminal ETLs return a
     * canonical catalog output type; intermediate ETLs return any
     * @Serializable payload type.
     */
    fun transform(
        dto: DTO,
        ctx: TransformCtx,
    ): OUT
}
