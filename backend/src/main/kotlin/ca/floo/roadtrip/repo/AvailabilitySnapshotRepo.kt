package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilitySnapshot.Companion.AVAILABILITY_SNAPSHOT
import ca.floo.roadtrip.models.api.AvailabilityDayDto
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.service.api.availabilityResponseJson
import kotlinx.serialization.encodeToString
import org.jooq.DSLContext
import org.jooq.JSONB
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import ca.floo.roadtrip.db.generated.enums.AvailabilityStatus as DbAvailabilityStatus

/**
 * Append-only per-day availability snapshots. Replaces the
 * earlier reservable_availability_log table.
 *
 * One snapshot per (reservable_id, target_date, observed_at). Window-level
 * availability is derived by combining consecutive target_date rows from the
 * same observed_at batch, so the snapshot timeline shows real per-day state.
 */
class AvailabilitySnapshotRepo(
    private val ctx: DSLContext,
) : AvailabilitySnapshotStore {
    data class SnapshotObservationBatch(
        val runId: Long? = null,
        val observations: List<SnapshotObservation>,
    )

    data class SnapshotObservation(
        val reservableId: Long,
        val reservableRid: String?,
        val targetDate: LocalDate,
        val observedAt: Instant,
        val status: AvailabilityStatus,
    )

    data class LatestObservation(
        val reservableId: Long,
        val targetDate: LocalDate,
        val observedAt: OffsetDateTime,
        val status: AvailabilityStatus,
        val available: Boolean,
    )

    override fun appendObservations(input: SnapshotObservationBatch): Int {
        if (input.observations.isEmpty()) return 0
        val inserts =
            input.observations.map { observation ->
                val observedAt = OffsetDateTime.ofInstant(observation.observedAt, ZoneOffset.UTC)
                ctx
                    .insertInto(AVAILABILITY_SNAPSHOT)
                    .set(AVAILABILITY_SNAPSHOT.RESERVABLE_ID, observation.reservableId)
                    .set(AVAILABILITY_SNAPSHOT.RUN_ID, input.runId)
                    .set(AVAILABILITY_SNAPSHOT.OBSERVED_AT, observedAt)
                    .set(AVAILABILITY_SNAPSHOT.TARGET_DATE, observation.targetDate)
                    .set(AVAILABILITY_SNAPSHOT.STATUS, observation.status.toDb())
                    .set(AVAILABILITY_SNAPSHOT.AVAILABLE, observation.status.isOnlineBookable)
                    .set(AVAILABILITY_SNAPSHOT.DAY_PAYLOAD, JSONB.valueOf(observation.toDayDto().toJson()))
            }
        ctx.batch(inserts).execute()
        return inserts.size
    }

    override fun loadLatestObservations(
        reservableIds: List<Long>,
        dates: List<LocalDate>,
    ): List<LatestObservation> {
        if (reservableIds.isEmpty() || dates.isEmpty()) return emptyList()
        return ctx
            .resultQuery(
                """
                SELECT DISTINCT ON (reservable_id, target_date)
                    id, reservable_id, target_date, status, available, observed_at
                FROM availability_snapshot
                WHERE reservable_id = ANY(?::bigint[])
                  AND target_date = ANY(?::date[])
                ORDER BY reservable_id, target_date, observed_at DESC, id DESC
                """.trimIndent(),
                reservableIds.toTypedArray(),
                dates.toTypedArray(),
            ).fetch { r ->
                LatestObservation(
                    reservableId = r.get("reservable_id", Long::class.java),
                    targetDate = r.get("target_date", LocalDate::class.java),
                    observedAt = r.get("observed_at", OffsetDateTime::class.java),
                    status = AvailabilityStatus.parse(r.get("status", String::class.java)),
                    available = r.get("available", Boolean::class.java),
                )
            }
    }

    private fun AvailabilityDayDto.toJson(): String = availabilityResponseJson.encodeToString(AvailabilityDayDto.serializer(), this)

    private fun SnapshotObservation.toDayDto(): AvailabilityDayDto =
        AvailabilityDayDto(
            date = targetDate.toString(),
            status = status,
            availableReservableIds = if (status.isOnlineBookable && reservableRid != null) listOf(reservableRid) else emptyList(),
            reservableStatuses = reservableRid?.let { mapOf(it to status) },
        )

    data class Snapshot(
        val id: Long,
        val reservableId: Long?,
        val runId: Long?,
        val targetDate: LocalDate,
        val observedAt: OffsetDateTime,
        val status: AvailabilityStatus,
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
        dates: List<LocalDate>,
        now: OffsetDateTime = OffsetDateTime.now(),
        windowHours: Int = 24 * 7,
    ): List<TargetDateStats> {
        if (dates.isEmpty()) return emptyList()
        val windowStart = now.minusHours(windowHours.toLong())
        val flipWindowStart = now.minusHours(24)
        val rows =
            ctx
                .selectFrom(AVAILABILITY_SNAPSHOT)
                .where(AVAILABILITY_SNAPSHOT.RESERVABLE_ID.eq(reservableId))
                .and(AVAILABILITY_SNAPSHOT.TARGET_DATE.`in`(dates))
                .and(AVAILABILITY_SNAPSHOT.OBSERVED_AT.ge(windowStart))
                .orderBy(
                    AVAILABILITY_SNAPSHOT.TARGET_DATE.asc(),
                    AVAILABILITY_SNAPSHOT.OBSERVED_AT.asc(),
                ).fetch { fromRecord(it) }
        val grouped = rows.groupBy { it.targetDate }
        return dates.map { date ->
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
            status = AvailabilityStatus.parse(r.get(AVAILABILITY_SNAPSHOT.STATUS)?.literal),
            available = r.get(AVAILABILITY_SNAPSHOT.AVAILABLE)!!,
            dayPayload = r.get(AVAILABILITY_SNAPSHOT.DAY_PAYLOAD)!!.data(),
        )
}

private fun AvailabilityStatus.toDb(): DbAvailabilityStatus =
    DbAvailabilityStatus.entries.firstOrNull { it.literal == wireValue }
        ?: error("availability status has no DB enum literal: $wireValue")
