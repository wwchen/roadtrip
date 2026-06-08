package ca.floo.roadtrip.etl

// Contract every per-source ETL implements. Stages are pure functions
// (no DB, no IO) — testable against captured raw fixtures.
//
// Per RFC decision #26: one file per source under etl/<source>/, all
// stages co-located. The interface gives a uniform shape to grep across
// (`SourceEtl<*, *>`); the per-source file keeps the cohesive mass.
interface SourceEtl<DTO, OUT : Poi> {
    // The `source` column value for every row this ETL emits. Must be
    // stable across runs — `pois.UNIQUE (source, source_id)` keys off it.
    val sourceName: String

    // True for paginated sources (PAD-US, BC Strapi) whose newest capture
    // is a directory of page-NNN.json files; the orchestrator hands every
    // page to [parseMulti]. False (default) for single-file captures —
    // [parse] is called with one envelope.
    val multiPart: Boolean get() = false

    // Verbatim upstream payload → strongly-typed DTO. Pure JSON →
    // data class deserialization; no transform, no merge.
    fun parse(envelope: Envelope): DTO = error("$sourceName uses multi-part captures; override parseMulti")

    // Multi-part variant. Default delegates to [parse] for single-page
    // sources so existing ETLs don't change.
    fun parseMulti(envelopes: List<Envelope>): DTO = error("$sourceName is single-part; override parse instead")

    // DTO row → ok | errors. Validation rules are per-source (required
    // fields, enum membership, geometry well-formedness, ID format).
    // Bad rows are counted but don't fail the run.
    fun validate(dto: DTO): ValidationResult<DTO>

    // DTO → domain Poi. Resolves dim-table FKs (governing_body,
    // booking_provider) via the context. Pure except for the read-only
    // dim-table lookups TransformCtx provides.
    fun transform(
        dto: DTO,
        ctx: TransformCtx,
    ): List<OUT>
}
