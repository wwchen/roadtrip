package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.ReservableAvailabilityLog.Companion.RESERVABLE_AVAILABILITY_LOG
import ca.floo.roadtrip.service.api.AvailabilityDayDto
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import ca.floo.roadtrip.service.api.availabilityResponseJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jooq.DSLContext
import org.jooq.JSONB
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ReservableAvailabilityLogRepo(
    private val ctx: DSLContext,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class AvailabilityPoll(
        val reservableRid: String,
        val response: AvailabilityResponseDto,
        val runId: Long? = null,
    )

    data class LogFilters(
        val id: Long? = null,
        val runId: Long? = null,
        val pollerId: Long? = null,
        val rid: String? = null,
        val targetDate: LocalDate? = null,
        val limit: Int = 100,
    )

    data class LogRow(
        val id: Long,
        val runId: Long?,
        val reservableRid: String,
        val observedAt: OffsetDateTime,
        val targetDate: LocalDate,
        val status: String,
        val available: Boolean,
        val dayPayload: JsonObject,
    )

    fun appendAvailabilityPoll(input: AvailabilityPoll): Int {
        require(input.reservableRid.isNotBlank()) { "reservableRid must not be blank" }

        if (input.response.availability.isEmpty()) return 0

        val observedAt = OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC)
        val inserts =
            input.response.availability.map { day ->
                ctx
                    .insertInto(RESERVABLE_AVAILABILITY_LOG)
                    .set(RESERVABLE_AVAILABILITY_LOG.RESERVABLE_RID, input.reservableRid)
                    .set(RESERVABLE_AVAILABILITY_LOG.OBSERVED_AT, observedAt)
                    .set(RESERVABLE_AVAILABILITY_LOG.TARGET_DATE, LocalDate.parse(day.date))
                    .set(RESERVABLE_AVAILABILITY_LOG.STATUS, day.status)
                    .set(RESERVABLE_AVAILABILITY_LOG.AVAILABLE, day.availableCount > 0)
                    .set(RESERVABLE_AVAILABILITY_LOG.DAY_PAYLOAD, JSONB.valueOf(day.toJson()))
                    .set(RESERVABLE_AVAILABILITY_LOG.RUN_ID, input.runId)
            }
        ctx.batch(inserts).execute()
        return inserts.size
    }

    fun list(filters: LogFilters): List<LogRow> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        filters.id?.let {
            where += "l.id = ?"
            args += it
        }
        filters.runId?.let {
            where += "l.run_id = ?"
            args += it
        }
        filters.pollerId?.let {
            where += "r.poller_id = ?"
            args += it
        }
        filters.rid?.let {
            where += "l.reservable_rid = ?"
            args += it
        }
        filters.targetDate?.let {
            where += "l.target_date = ?"
            args += it
        }
        args += filters.limit
        val whereSql = if (where.isEmpty()) "" else "WHERE ${where.joinToString(" AND ")}"
        return ctx
            .fetch(
                """
                SELECT l.*
                FROM reservable_availability_log l
                LEFT JOIN reservable_availability_runs r ON r.id = l.run_id
                $whereSql
                ORDER BY l.observed_at DESC, l.id DESC
                LIMIT ?
                """.trimIndent(),
                *args.toTypedArray(),
            ).map { record ->
                LogRow(
                    id = record.get("id", Long::class.java),
                    runId = record.get("run_id", Long::class.java),
                    reservableRid = record.get("reservable_rid", String::class.java),
                    observedAt = record.get("observed_at", OffsetDateTime::class.java),
                    targetDate = record.get("target_date", LocalDate::class.java),
                    status = record.get("status", String::class.java),
                    available = record.get("available", Boolean::class.java),
                    dayPayload = Json.parseToJsonElement(record.get("day_payload", JSONB::class.java).data()).jsonObject,
                )
            }
    }

    private fun AvailabilityDayDto.toJson(): String = availabilityResponseJson.encodeToString(AvailabilityDayDto.serializer(), this)
}
