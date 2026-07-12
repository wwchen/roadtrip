package ca.floo.roadtrip.models.domain.ingest

import java.time.OffsetDateTime

data class IngestRunDetailRow(
    val id: Long,
    val target: String,
    val kind: String,
    val status: String,
    val triggeredBy: String,
    val startedAt: OffsetDateTime,
    val completedAt: OffsetDateTime?,
    val notes: String?,
    val phases: List<IngestRunPhaseRow>,
)
