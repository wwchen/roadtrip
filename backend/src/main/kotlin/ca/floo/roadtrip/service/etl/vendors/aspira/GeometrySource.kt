package ca.floo.roadtrip.service.etl.vendors.aspira

/**
 * A geometry feed, read as a flat stream of named points. Normalization and
 * first-writer-wins merging belong to [GeometryIndex]; a source only parses.
 */
sealed interface GeometrySource {
    fun points(): Sequence<NamedPoint>
}
