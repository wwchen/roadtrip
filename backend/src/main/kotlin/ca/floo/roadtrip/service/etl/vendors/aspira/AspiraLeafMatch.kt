package ca.floo.roadtrip.service.etl.vendors.aspira

/** A bookable leaf paired with the geometry value its name resolved to. */
data class AspiraLeafMatch<T>(
    val leaf: AspiraLeaf,
    val value: T,
    val kind: AspiraLeafMatchKind,
)
