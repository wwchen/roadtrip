package ca.floo.roadtrip.repo

data class TargetIngestStatusRow(
    val target: String,
    val lastRun: Long? = null,
    val kind: String? = null,
    val status: String? = null,
    val ageSec: Long? = null,
)
