package ca.floo.roadtrip.ingest

// Vocabulary:
//   fetch  = web → local file in data/   (Phase.Fetch, runs a Python script)
//   import = local file → Postgres rows   (Phase.Import, runs Importer.run)
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

    data class Import(
        override val label: String,
        val sourceName: String,
    ) : Phase
}

// A unit of refresh producing one coherent on-disk artifact (one
// data_source from config/poi-registry.yaml). One target = one mutex;
// fetch + import on the same target serialize.
data class Target(
    val name: String,
    val fetchPhases: List<Phase.Fetch>,
    val importPhases: List<Phase.Import>,
)
