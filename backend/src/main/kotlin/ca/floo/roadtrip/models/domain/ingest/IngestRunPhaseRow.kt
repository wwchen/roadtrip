package ca.floo.roadtrip.models.domain.ingest

import java.time.OffsetDateTime

data class IngestRunPhaseRow(
    val id: Long,
    val phase: String,
    val phaseKind: String,
    val status: String,
    val exitCode: Int?,
    val startedAt: OffsetDateTime,
    val completedAt: OffsetDateTime?,
    val countsJson: String?,
    val notes: String?,
)
