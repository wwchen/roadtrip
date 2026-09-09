package ca.floo.roadtrip.model.metadata.ingest

enum class RunStatus(
    val wire: String,
) {
    COMPLETED("completed"),
    FAILED("failed"),
    NOOP("noop"),
    BUSY("busy"),
}
