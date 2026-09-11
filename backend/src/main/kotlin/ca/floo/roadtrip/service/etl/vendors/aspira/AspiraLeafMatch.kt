package ca.floo.roadtrip.service.etl.vendors.aspira

/** A bookable leaf paired with the geometry value its name resolved to. */
data class AspiraLeafMatch<T>(
    val leaf: AspiraLeaf,
    val value: T,
    val kind: AspiraLeafMatchKind,
    /** The index key the leaf resolved to: the normalized name of the winning feed entry. */
    val matchedName: String,
    /** Jaccard token overlap for [AspiraLeafMatchKind.FUZZY]; null for the deterministic kinds. */
    val score: Double? = null,
)
