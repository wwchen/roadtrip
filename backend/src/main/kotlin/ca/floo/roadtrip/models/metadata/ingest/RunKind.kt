package ca.floo.roadtrip.models.metadata.ingest

// What a backend ingest run does. Fetchers run outside the Ktor app; backend
// ingest runs import existing raw captures into Postgres.
enum class RunKind(
    val rowValue: String,
) {
    IMPORT("import"),
}
