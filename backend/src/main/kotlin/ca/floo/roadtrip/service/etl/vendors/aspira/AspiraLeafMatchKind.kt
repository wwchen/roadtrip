package ca.floo.roadtrip.service.etl.vendors.aspira

/** How a leaf found its geometry; [label] is what the metadata payload records. */
enum class AspiraLeafMatchKind(
    val label: String,
) {
    EXACT("exact"),
    FUZZY("fuzzy"),
    PARENT("parent"),
}
