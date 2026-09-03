package ca.floo.roadtrip.service.etl.vendors.aspira

/**
 * How an Aspira leaf reached its coordinates in [AspiraGeometryMatcher],
 * in ladder order.
 *
 * [wireValue] is persisted: it is the `match_kind` written into every
 * campground's `metadata` and `source_payload`, so it must stay stable even
 * if the constants are renamed.
 */
enum class MatchKind(
    val wireValue: String,
) {
    /** The leaf's own normalized name is a key in the geometry index. */
    EXACT("exact"),

    /** Best Jaccard token overlap with an index key is at least [AspiraGeometryMatcher.FUZZY_THRESHOLD]. */
    FUZZY("fuzzy"),

    /** The leaf's own name missed, but its `parent_name` is a key in the index. */
    PARENT("parent"),
}
