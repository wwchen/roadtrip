package ca.floo.roadtrip.models.metadata.ingest

// A unit of refresh.
// One target per runnable poi_data/campsite_data row. Target.name = row display name.
// Per-target mutex serializes concurrent runs of the same target.
data class Target(
    val name: String,
    val importPhases: List<Phase.Import>,
)
