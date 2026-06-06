package ca.floo.roadtrip.ingest

// One step within a target. Shell phases run a Python (or Make) script as a
// subprocess; Kotlin phases call the in-process Importer against a named
// Source. Failure of any phase aborts the rest of the target's phases.
sealed interface Phase {
    val label: String

    data class Shell(
        override val label: String,
        val cmd: List<String>,
        val timeoutSec: Long = 30 * 60,
    ) : Phase

    data class Kotlin(
        override val label: String,
        val sourceName: String,
    ) : Phase
}

// A unit of refresh producing one coherent on-disk artifact (campgrounds,
// planet-fitness, …). The lock is per-target — multiple scripts that share
// a file (campgrounds.geojson) all live under one target so they can't
// interleave.
data class Target(
    val name: String,
    val phases: List<Phase>,
)
