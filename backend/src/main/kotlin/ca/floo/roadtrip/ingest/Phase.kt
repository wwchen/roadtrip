package ca.floo.roadtrip.ingest

import ca.floo.roadtrip.importer.HttpFetchSource

// Vocabulary:
//   fetch  = web → local file in data/   (Phase.Fetch — Shell or Kotlin variant)
//   import = local file → Postgres rows   (Phase.Import, runs Importer.run)
//
// "ingest" is the umbrella term for fetch + import together; it appears only
// in internal code (this package, the ingest_runs table). End-user surfaces
// (Make targets, Tilt buttons, README) say data-fetch / data-import.
sealed interface Phase {
    val label: String

    sealed interface Fetch : Phase {
        data class Shell(
            override val label: String,
            val cmd: List<String>,
            val timeoutSec: Long = 30 * 60,
        ) : Fetch

        // RFC 0004 step 3: in-process Kotlin fetcher. The source builds the
        // same data/<name>.{json,geojson} a Python script would, but inside
        // the JVM — no subprocess, no Python on PATH, shared HTTP client.
        data class Kotlin(
            override val label: String,
            val source: HttpFetchSource,
        ) : Fetch
    }

    data class Import(
        override val label: String,
        val sourceName: String,
    ) : Phase
}

// A unit of refresh producing one coherent on-disk artifact (campgrounds,
// planet-fitness, …). One target = one mutex; fetch + import on the same
// target serialize. Targets that share an artifact (campgrounds.geojson)
// keep all their fetch phases under one target so they can't interleave.
data class Target(
    val name: String,
    val fetchPhases: List<Phase.Fetch>,
    val importPhases: List<Phase.Import>,
)
