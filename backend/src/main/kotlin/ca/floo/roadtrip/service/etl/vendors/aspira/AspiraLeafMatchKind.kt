package ca.floo.roadtrip.service.etl.vendors.aspira

/** How a leaf found its geometry; [label] is what the geometry provenance records. */
enum class AspiraLeafMatchKind(
    val label: String,
) {
    EXACT("exact"),
    FUZZY("fuzzy"),
    PARENT("parent"),
}
