package ca.floo.roadtrip.models.ingest

// Vocabulary:
//   fetch  = web → local file in data/raw/   (Phase.Fetch, runs a fetcher subprocess)
//   import = data/raw/ + data/etl-out/ → Postgres rows  (Phase.Import, runs EtlOrchestrator)
//
// "ingest" is the umbrella term for fetch + import together; it appears only
// in internal code (this package, the ingest_runs table). End-user surfaces
// (Make targets, Tilt buttons, README) say data-fetch / data-import.
sealed interface Phase {
    val label: String

    data class Fetch(
        override val label: String,
        val cmd: List<String>,
        val timeoutSec: Long = 30 * 60,
    ) : Phase

    /**
     * Run a poi_data row's full etls chain. [poiDataName] is the row's
     * display name from YAML; the orchestrator looks it up and walks the
     * chain end-to-end.
     */
    data class Import(
        override val label: String,
        val poiDataName: String,
    ) : Phase
}

// A unit of refresh.
//   - Fetch targets: one per data_sources row. Target.name = data_source slug.
//   - Import targets: one per poi_data row. Target.name = poi_data display name.
// Per-target mutex serializes concurrent runs of the same target.
data class Target(
    val name: String,
    val fetchPhases: List<Phase.Fetch>,
    val importPhases: List<Phase.Import>,
)

// What a run does. Each target has both a fetch list (web → data/) and an
// import list (data/ → Postgres). They share a per-target mutex so a fetch
// and an import on the same target serialize.
enum class RunKind(
    val rowValue: String,
) {
    FETCH("fetch"),
    IMPORT("import"),
}

data class RunOutcome(
    val parentRunId: Long,
    val target: String,
    val kind: RunKind,
    val status: String, // 'completed' | 'failed' | 'noop'
    val failedPhase: String?,
)
