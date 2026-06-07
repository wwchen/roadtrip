package ca.floo.roadtrip.importer

import io.ktor.client.HttpClient
import java.io.File

// In-process Kotlin fetcher for one POI source. Pulls upstream over HTTP,
// transforms to the same data/<name>.{json,geojson} a Python script would
// have written, stamps _fetched_at, and persists. The Importer reads the
// file via the existing Source for that name unchanged — file is the
// boundary, no schema or import-side change.
//
// Per RFC 0004 step 3 (porting fetchers one at a time). New ones extend
// this; the IngestController's Phase.Fetch.Kotlin variant invokes them.
//
// Counts/notes the IngestController surfaces: returned as a JSON string
// stored in ingest_runs.counts.
interface HttpFetchSource {
    val name: String

    suspend fun fetch(
        client: HttpClient,
        dataDir: File,
    ): FetchOutcome
}

data class FetchOutcome(
    val featureCount: Int,
    val outputFile: File,
)
