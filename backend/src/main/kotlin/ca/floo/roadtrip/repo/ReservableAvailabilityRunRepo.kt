package ca.floo.roadtrip.repo

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jooq.DSLContext
import java.time.OffsetDateTime

class ReservableAvailabilityRunRepo(
    private val ctx: DSLContext,
) {
    private val json = Json

    data class Run(
        val id: Long,
        val sourceKind: String,
        val pollerId: Long?,
        val status: String,
        val candidateCount: Int,
        val logCount: Int,
        val error: String?,
        val startedAt: OffsetDateTime,
        val completedAt: OffsetDateTime?,
    )

    fun start(
        sourceKind: String,
        pollerId: Long?,
        intentPayload: JsonObject,
    ): Run {
        val id =
            ctx
                .fetchOne(
                    """
                    INSERT INTO reservable_availability_runs (
                      source_kind, poller_id, intent_payload, status
                    ) VALUES (
                      ?, ?, ?::jsonb, 'started'
                    )
                    RETURNING id
                    """.trimIndent(),
                    sourceKind,
                    pollerId,
                    json.encodeToString(intentPayload),
                )!!
                .get("id", Long::class.java)
        return get(id)!!
    }

    fun complete(
        id: Long,
        candidateCount: Int,
        logCount: Int,
    ): Run {
        ctx.execute(
            """
            UPDATE reservable_availability_runs
            SET status = 'completed',
                candidate_count = ?,
                log_count = ?,
                completed_at = now()
            WHERE id = ?
            """.trimIndent(),
            candidateCount,
            logCount,
            id,
        )
        return get(id)!!
    }

    fun fail(
        id: Long,
        error: String,
        candidateCount: Int = 0,
        logCount: Int = 0,
    ): Run {
        ctx.execute(
            """
            UPDATE reservable_availability_runs
            SET status = 'failed',
                candidate_count = ?,
                log_count = ?,
                error = ?,
                completed_at = now()
            WHERE id = ?
            """.trimIndent(),
            candidateCount,
            logCount,
            error.take(1000),
            id,
        )
        return get(id)!!
    }

    fun get(id: Long): Run? =
        ctx
            .fetchOne("SELECT * FROM reservable_availability_runs WHERE id = ?", id)
            ?.let(::fromRecord)

    fun list(
        limit: Int = 100,
        offset: Int = 0,
        pollerId: Long? = null,
    ): List<Run> {
        val where = if (pollerId == null) "" else "WHERE poller_id = ?"
        val args =
            if (pollerId == null) {
                arrayOf<Any?>(limit, offset)
            } else {
                arrayOf<Any?>(pollerId, limit, offset)
            }
        return ctx
            .fetch(
                """
                SELECT *
                FROM reservable_availability_runs
                $where
                ORDER BY started_at DESC, id DESC
                LIMIT ? OFFSET ?
                """.trimIndent(),
                *args,
            ).map(::fromRecord)
    }

    private fun fromRecord(r: org.jooq.Record): Run =
        Run(
            id = r.get("id", Long::class.java),
            sourceKind = r.get("source_kind", String::class.java),
            pollerId = r.get("poller_id", Long::class.java),
            status = r.get("status", String::class.java),
            candidateCount = r.get("candidate_count", Int::class.java),
            logCount = r.get("log_count", Int::class.java),
            error = r.get("error", String::class.java),
            startedAt = r.get("started_at", OffsetDateTime::class.java),
            completedAt = r.get("completed_at", OffsetDateTime::class.java),
        )
}
