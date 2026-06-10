package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.IngestRuns.Companion.INGEST_RUNS
import org.jooq.DSLContext
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

class AdminIngestReadRepo(
    private val ctx: DSLContext,
) {
    fun listRecent(
        target: String?,
        limit: Int,
    ): List<IngestRunListItemRow> {
        val records =
            ctx
                .select(
                    INGEST_RUNS.ID,
                    INGEST_RUNS.TARGET,
                    INGEST_RUNS.PHASE,
                    INGEST_RUNS.STATUS,
                    INGEST_RUNS.STARTED_AT,
                    INGEST_RUNS.COMPLETED_AT,
                    INGEST_RUNS.TRIGGERED_BY,
                ).from(INGEST_RUNS)
                .where(INGEST_RUNS.PHASE_KIND.eq("target"))
                .apply { if (target != null) and(INGEST_RUNS.TARGET.eq(target)) }
                .orderBy(INGEST_RUNS.STARTED_AT.desc())
                .limit(limit)
                .fetch()

        return records.map { record ->
            IngestRunListItemRow(
                id = record.get(INGEST_RUNS.ID)!!,
                target = record.get(INGEST_RUNS.TARGET)!!,
                kind = record.get(INGEST_RUNS.PHASE)!!,
                status = record.get(INGEST_RUNS.STATUS)!!,
                triggeredBy = record.get(INGEST_RUNS.TRIGGERED_BY)!!,
                startedAt = record.get(INGEST_RUNS.STARTED_AT)!!,
                completedAt = record.get(INGEST_RUNS.COMPLETED_AT),
            )
        }
    }

    fun runDetail(id: Long): IngestRunDetailRow? {
        val parent =
            ctx
                .selectFrom(INGEST_RUNS)
                .where(INGEST_RUNS.ID.eq(id))
                .and(INGEST_RUNS.PHASE_KIND.eq("target"))
                .fetchOne() ?: return null

        val phases =
            ctx
                .selectFrom(INGEST_RUNS)
                .where(INGEST_RUNS.PARENT_RUN_ID.eq(id))
                .orderBy(INGEST_RUNS.ID.asc())
                .fetch()
                .map { phase ->
                    IngestRunPhaseRow(
                        id = phase.id!!,
                        phase = phase.phase!!,
                        phaseKind = phase.phaseKind!!,
                        status = phase.status!!,
                        exitCode = phase.exitCode,
                        startedAt = phase.startedAt!!,
                        completedAt = phase.completedAt,
                        countsJson = phase.counts?.data(),
                        notes = phase.notes,
                    )
                }

        return IngestRunDetailRow(
            id = parent.id!!,
            target = parent.target!!,
            kind = parent.phase!!,
            status = parent.status!!,
            triggeredBy = parent.triggeredBy!!,
            startedAt = parent.startedAt!!,
            completedAt = parent.completedAt,
            notes = parent.notes,
            phases = phases,
        )
    }

    fun statusByTarget(targets: Set<String>): List<TargetIngestStatusRow> {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        return targets.sorted().map { target ->
            val latest =
                ctx
                    .selectFrom(INGEST_RUNS)
                    .where(INGEST_RUNS.TARGET.eq(target))
                    .and(INGEST_RUNS.PHASE_KIND.eq("target"))
                    .and(INGEST_RUNS.STATUS.`in`("completed", "failed"))
                    .orderBy(INGEST_RUNS.STARTED_AT.desc())
                    .limit(1)
                    .fetchOne()

            if (latest == null) {
                TargetIngestStatusRow(target = target)
            } else {
                TargetIngestStatusRow(
                    target = target,
                    lastRun = latest.id,
                    kind = latest.phase!!,
                    status = latest.status!!,
                    ageSec = Duration.between(latest.completedAt ?: latest.startedAt, now).seconds,
                )
            }
        }
    }
}

data class IngestRunListItemRow(
    val id: Long,
    val target: String,
    val kind: String,
    val status: String,
    val triggeredBy: String,
    val startedAt: OffsetDateTime,
    val completedAt: OffsetDateTime?,
)

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

data class TargetIngestStatusRow(
    val target: String,
    val lastRun: Long? = null,
    val kind: String? = null,
    val status: String? = null,
    val ageSec: Long? = null,
)
