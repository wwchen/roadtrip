package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.metadata.Envelope
import kotlinx.serialization.json.JsonElement

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
