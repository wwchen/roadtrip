package ca.floo.roadtrip.etl

import kotlinx.serialization.json.JsonElement

// Contract every per-row ETL implements. Stages are pure functions
// (no DB, no IO) — testable against captured raw fixtures.
//
// Inputs: declared in YAML as the etl's `inputs:` list. Each input slug
// resolves to either a data_source (whose newest envelope(s) get loaded
// from data/raw/<slug>/) or an earlier sibling etl in the same poi_data
// row (whose typed payload gets loaded from data/etl-out/<slug>/). The
// orchestrator hands the ETL an InputBundle keyed by slug.
//
// Outputs: an intermediate ETL returns any @Serializable payload; the
// orchestrator writes it as JSON to data/etl-out/<etl-slug>/<UTC-ts>.json.
// A terminal ETL (the last entry in a poi_data row's etls: list) returns
// List<Poi.*>; the orchestrator hands it to Upsert.run.
//
// Per RFC decision #26: one file per ETL under etl/<vendor>/, all stages
// co-located. The interface gives a uniform shape to grep across; the
// per-vendor file keeps the cohesive mass.
interface SourceEtl<DTO, OUT> {
    /**
     * The etl's YAML slug. Must match exactly. Terminal ETLs use this as
     * `pois.source` so the upsert sweep stays scoped per-terminal.
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
     * provides (subcategory). Terminal ETLs return List<Poi.*>;
     * intermediate ETLs return any @Serializable payload type.
     */
    fun transform(
        dto: DTO,
        ctx: TransformCtx,
    ): OUT
}

/**
 * Per-input accessor handed to [SourceEtl.parse]. Resolves an `inputs:`
 * slug from the YAML to either:
 *  - data_source: the newest envelope(s) from data/raw/<slug>/
 *  - prior etl in the same poi_data row: the parsed JsonElement payload
 *    from the etl's newest output under data/etl-out/<slug>/
 *
 * Calling an accessor for a slug not in the etl's declared inputs is a
 * programming error — the YAML validator would have rejected it.
 */
class InputBundle(
    /** Insertion-ordered: the YAML `inputs:` list order. */
    private val rawCaptures: LinkedHashMap<String, List<Envelope>>,
    private val etlOutputs: LinkedHashMap<String, JsonElement>,
) {
    /** All envelopes from the newest capture of [slug]. */
    fun envelopes(slug: String): List<Envelope> =
        rawCaptures[slug]
            ?: error("input '$slug' is not a declared data_source input")

    /** Single-envelope shorthand. Errors if the data_source is multi-part. */
    fun envelope(slug: String): Envelope {
        val envs = envelopes(slug)
        require(envs.size == 1) {
            "input '$slug' is multi-part (${envs.size} pages); use envelopes(slug) instead"
        }
        return envs.single()
    }

    /** The deserialized payload of an upstream etl's newest run. */
    fun etlOutput(slug: String): JsonElement =
        etlOutputs[slug]
            ?: error("input '$slug' is not a declared etl input")

    /**
     * Convenience for ETLs that consume a single data_source input. Returns
     * the envelopes from the first (and only) data_source declared in the
     * YAML's `inputs:`. Errors if there are zero or more than one.
     */
    fun soleEnvelopes(): List<Envelope> {
        require(rawCaptures.size == 1) {
            "soleEnvelopes() requires exactly one data_source input; got ${rawCaptures.size}"
        }
        return rawCaptures.values.first()
    }

    /** Slugs in YAML declaration order, for ETLs whose dispatch is per-input. */
    fun dataSourceSlugs(): List<String> = rawCaptures.keys.toList()

    /** Same, for the subset that resolved to upstream ETL outputs. */
    fun etlSlugs(): List<String> = etlOutputs.keys.toList()
}
