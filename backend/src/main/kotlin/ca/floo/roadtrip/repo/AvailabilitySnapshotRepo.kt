package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilitySnapshot.Companion.AVAILABILITY_SNAPSHOT
import ca.floo.roadtrip.service.api.AvailabilityDayDto
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import ca.floo.roadtrip.service.api.availabilityResponseJson
import kotlinx.serialization.encodeToString
import org.jooq.DSLContext
import org.jooq.JSONB
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Append-only per-day single-night availability snapshots. Replaces the
 * earlier reservable_availability_log table.
 *
 * One snapshot per (reservable_id, target_date, observed_at). Multi-
 * night availability is derived by combining consecutive target_date
 * rows from the same observed_at batch — the executor stores
 * single-night data even when the request was multi-night, so the
 * snapshot timeline shows real per-day state regardless of the original
 * query's min_nights.
 */
class AvailabilitySnapshotRepo(
    private val ctx: DSLContext,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class SnapshotBatch(
        val reservableId: Long,
        val runId: Long?,
        val response: AvailabilityResponseDto,
    )

    fun appendBatch(input: SnapshotBatch): Int {
        if (input.response.availability.isEmpty()) return 0

        val observedAt = OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC)
        val inserts =
            input.response.availability.map { day ->
                ctx
                    .insertInto(AVAILABILITY_SNAPSHOT)
                    .set(AVAILABILITY_SNAPSHOT.RESERVABLE_ID, input.reservableId)
                    .set(AVAILABILITY_SNAPSHOT.RUN_ID, input.runId)
                    .set(AVAILABILITY_SNAPSHOT.OBSERVED_AT, observedAt)
                    .set(AVAILABILITY_SNAPSHOT.TARGET_DATE, LocalDate.parse(day.date))
                    .set(AVAILABILITY_SNAPSHOT.STATUS, day.status)
                    .set(AVAILABILITY_SNAPSHOT.AVAILABLE, day.availableCount > 0)
                    .set(AVAILABILITY_SNAPSHOT.DAY_PAYLOAD, JSONB.valueOf(day.toJson()))
            }
        ctx.batch(inserts).execute()
        return inserts.size
    }

    private fun AvailabilityDayDto.toJson(): String = availabilityResponseJson.encodeToString(AvailabilityDayDto.serializer(), this)

    data class Snapshot(
        val id: Long,
        val reservableId: Long?,
        val runId: Long?,
        val targetDate: LocalDate,
        val observedAt: OffsetDateTime,
        val status: String,
        val available: Boolean,
        val dayPayload: String,
    )

    fun listForReservable(
        reservableId: Long,
        limit: Int = 200,
    ): List<Snapshot> =
        ctx
            .selectFrom(AVAILABILITY_SNAPSHOT)
            .where(AVAILABILITY_SNAPSHOT.RESERVABLE_ID.eq(reservableId))
            .orderBy(
                AVAILABILITY_SNAPSHOT.TARGET_DATE.desc(),
                AVAILABILITY_SNAPSHOT.OBSERVED_AT.desc(),
                AVAILABILITY_SNAPSHOT.ID.desc(),
            ).limit(limit.coerceIn(1, 1000))
            .fetch { fromRecord(it) }

    fun listForRun(
        runId: Long,
        limit: Int = 500,
    ): List<Snapshot> =
        ctx
            .selectFrom(AVAILABILITY_SNAPSHOT)
            .where(AVAILABILITY_SNAPSHOT.RUN_ID.eq(runId))
            .orderBy(AVAILABILITY_SNAPSHOT.TARGET_DATE.asc())
            .limit(limit.coerceIn(1, 1000))
            .fetch { fromRecord(it) }

    data class TargetDateStats(
        val targetDate: LocalDate,
        val totalSnapshots: Int,
        val lastOpenAt: OffsetDateTime?,
        val isCurrentlyOpen: Boolean,
        val currentOrLastOpenWindowSec: Int?,
        val medianOpenWindowSec: Int?,
        val flipsLast24h: Int,
    )

    /**
     * Per-target-date stats computed from the snapshot rows in the
     * given window. The window applies to `observed_at`; every snapshot
     * within it counts toward `totalSnapshots`. flipsLast24h is always
     * computed over a fixed 24h tail regardless of window length.
     *
     * Empty input (no snapshots for that target_date in window) yields
     * an entry with totalSnapshots=0 so the UI can render "never seen
     * open" rather than dropping the date.
     */
    fun summarize(
        reservableId: Long,
        targetDates: List<LocalDate>,
        now: OffsetDateTime = OffsetDateTime.now(),
        windowHours: Int = 24 * 7,
    ): List<TargetDateStats> {
        if (targetDates.isEmpty()) return emptyList()
        val windowStart = now.minusHours(windowHours.toLong())
        val flipWindowStart = now.minusHours(24)
        val rows =
            ctx
                .selectFrom(AVAILABILITY_SNAPSHOT)
                .where(AVAILABILITY_SNAPSHOT.RESERVABLE_ID.eq(reservableId))
                .and(AVAILABILITY_SNAPSHOT.TARGET_DATE.`in`(targetDates))
                .and(AVAILABILITY_SNAPSHOT.OBSERVED_AT.ge(windowStart))
                .orderBy(
                    AVAILABILITY_SNAPSHOT.TARGET_DATE.asc(),
                    AVAILABILITY_SNAPSHOT.OBSERVED_AT.asc(),
                ).fetch { fromRecord(it) }
        val grouped = rows.groupBy { it.targetDate }
        return targetDates.map { date ->
            val group = grouped[date].orEmpty()
            statsFor(date, group, flipWindowStart)
        }
    }

    private fun statsFor(
        date: LocalDate,
        snapshots: List<Snapshot>,
        flipWindowStart: OffsetDateTime,
    ): TargetDateStats {
        if (snapshots.isEmpty()) {
            return TargetDateStats(
                targetDate = date,
                totalSnapshots = 0,
                lastOpenAt = null,
                isCurrentlyOpen = false,
                currentOrLastOpenWindowSec = null,
                medianOpenWindowSec = null,
                flipsLast24h = 0,
            )
        }

        // Walk for contiguous available=true runs.
        data class Run(
            val start: OffsetDateTime,
            val end: OffsetDateTime,
        )
        val runs = mutableListOf<Run>()
        var runStart: OffsetDateTime? = null
        var lastTrueAt: OffsetDateTime? = null
        for (s in snapshots) {
            if (s.available) {
                if (runStart == null) runStart = s.observedAt
                lastTrueAt = s.observedAt
            } else if (runStart != null) {
                runs += Run(start = runStart, end = lastTrueAt!!)
                runStart = null
            }
        }
        val isCurrentlyOpen = snapshots.last().available
        if (runStart != null) {
            runs += Run(start = runStart, end = lastTrueAt!!)
        }
        val currentOrLastOpenWindowSec =
            runs.lastOrNull()?.let {
                java.time.Duration
                    .between(it.start, it.end)
                    .seconds
                    .toInt()
                    .coerceAtLeast(0)
            }
        val medianOpenWindowSec =
            if (runs.isEmpty()) {
                null
            } else {
                val durations =
                    runs
                        .map {
                            java.time.Duration
                                .between(it.start, it.end)
                                .seconds
                                .toInt()
                                .coerceAtLeast(0)
                        }.sorted()
                val mid = durations.size / 2
                if (durations.size % 2 == 0) {
                    (durations[mid - 1] + durations[mid]) / 2
                } else {
                    durations[mid]
                }
            }
        // Count false→true transitions within the last 24h.
        var flips = 0
        var prev: Snapshot? = null
        for (s in snapshots) {
            if (s.observedAt >= flipWindowStart && prev != null && !prev.available && s.available) {
                flips += 1
            }
            prev = s
        }
        return TargetDateStats(
            targetDate = date,
            totalSnapshots = snapshots.size,
            lastOpenAt = lastTrueAt,
            isCurrentlyOpen = isCurrentlyOpen,
            currentOrLastOpenWindowSec = currentOrLastOpenWindowSec,
            medianOpenWindowSec = medianOpenWindowSec,
            flipsLast24h = flips,
        )
    }

    private fun fromRecord(r: org.jooq.Record): Snapshot =
        Snapshot(
            id = r.get(AVAILABILITY_SNAPSHOT.ID)!!,
            reservableId = r.get(AVAILABILITY_SNAPSHOT.RESERVABLE_ID),
            runId = r.get(AVAILABILITY_SNAPSHOT.RUN_ID),
            targetDate = r.get(AVAILABILITY_SNAPSHOT.TARGET_DATE)!!,
            observedAt = r.get(AVAILABILITY_SNAPSHOT.OBSERVED_AT)!!,
            status = r.get(AVAILABILITY_SNAPSHOT.STATUS)!!,
            available = r.get(AVAILABILITY_SNAPSHOT.AVAILABLE)!!,
            dayPayload = r.get(AVAILABILITY_SNAPSHOT.DAY_PAYLOAD)!!.data(),
        )
}
