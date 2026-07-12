package ca.floo.roadtrip.models.metadata.ingest

// A unit of refresh.
//   - Fetch targets: one per data_sources row. Target.name = data_source slug.
//   - Import targets: one per runnable poi_data/campsite_data row.
//     Target.name = row display name.
// Per-target mutex serializes concurrent runs of the same target.
data class Target(
    val name: String,
    val fetchPhases: List<Phase.Fetch>,
    val importPhases: List<Phase.Import>,
)
