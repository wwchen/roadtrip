package ca.floo.roadtrip.model.domain.ingest

import java.time.OffsetDateTime

data class IngestRunListItemRow(
    val id: Long,
    val target: String,
    val kind: String,
    val status: String,
    val triggeredBy: String,
    val startedAt: OffsetDateTime,
    val completedAt: OffsetDateTime?,
)
