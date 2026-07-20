package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.metadata.Envelope

/**
 * Per-input accessor handed to [SourceEtl.parse]. Resolves an `inputs:` slug
 * from the YAML to the newest envelope(s) from its data_source
 * output_dir_prefix.
 *
 * Calling an accessor for a slug not in the etl's declared inputs is a
 * programming error — the YAML validator would have rejected it.
 */
class InputBundle(
    /** Insertion-ordered: the YAML `inputs:` list order. */
    private val rawCaptures: LinkedHashMap<String, List<Envelope>>,
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
}
