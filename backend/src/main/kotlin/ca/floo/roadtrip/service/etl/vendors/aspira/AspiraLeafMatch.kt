package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.domain.GeometryProvenance

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

/** The one place a match becomes the provenance an emitted campground records. */
fun AspiraLeafMatch<GeometryPoint>.toProvenance(): GeometryProvenance =
    GeometryProvenance(matchKind = kind.label, source = value.source, matchedName = matchedName, score = score)
