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

    override fun pruneObservationsBefore(
        reservableIds: List<Long>,
        cutoff: Instant,
    ): Int {
        if (reservableIds.isEmpty()) return 0
        return ctx
            .deleteFrom(AVAILABILITY_SNAPSHOT)
            .where(AVAILABILITY_SNAPSHOT.RESERVABLE_ID.`in`(reservableIds))
            .and(AVAILABILITY_SNAPSHOT.OBSERVED_AT.lt(OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC)))
            .execute()
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
        // Snapshots are edge-only (both the poller and the on-demand read path
        // append only status changes), so the window can be empty for a cell
        // whose last edge predates it. Take authoritative current state from the
        // `availability_cell` cube, and seed each group with the last snapshot
        // before the window so run/flip detection knows the entering state.
        val cellByDate = AvailabilityCellRepo(ctx).loadCells(listOf(reservableId), dates).associateBy { it.targetDate }
        val seedByDate = latestSnapshotBefore(reservableId, dates, windowStart)
        return dates.map { date ->
            statsFor(
                date = date,
                windowRows = grouped[date].orEmpty(),
                seed = seedByDate[date],
                currentCell = cellByDate[date],
                flipWindowStart = flipWindowStart,
                now = now,
            )
        }
    }

    /** Newest snapshot strictly before [before] per date — the carry-forward seed. */
    private fun latestSnapshotBefore(
        reservableId: Long,
        dates: List<LocalDate>,
        before: OffsetDateTime,
    ): Map<LocalDate, Snapshot> {
        if (dates.isEmpty()) return emptyMap()
        return ctx
            .select(AVAILABILITY_SNAPSHOT.fields().toList())
            .distinctOn(AVAILABILITY_SNAPSHOT.TARGET_DATE)
            .from(AVAILABILITY_SNAPSHOT)
            .where(AVAILABILITY_SNAPSHOT.RESERVABLE_ID.eq(reservableId))
            .and(AVAILABILITY_SNAPSHOT.TARGET_DATE.`in`(dates))
            .and(AVAILABILITY_SNAPSHOT.OBSERVED_AT.lt(before))
            .orderBy(
                AVAILABILITY_SNAPSHOT.TARGET_DATE,
                AVAILABILITY_SNAPSHOT.OBSERVED_AT.desc(),
                AVAILABILITY_SNAPSHOT.ID.desc(),
            ).fetch { fromRecord(it) }
            .associateBy { it.targetDate }
    }

    private fun statsFor(
        date: LocalDate,
        windowRows: List<Snapshot>,
        seed: Snapshot?,
        currentCell: AvailabilityCellRepo.Cell?,
        flipWindowStart: OffsetDateTime,
        now: OffsetDateTime,
    ): TargetDateStats {
        // Authoritative current state is the cube; snapshots are edge-only history.
        // Fall back to the newest sample only when the cube has no cell (e.g. unit
        // tests, or a reservable never written to the cube).
        val samples = if (seed != null) listOf(seed) + windowRows else windowRows
        val isCurrentlyOpen =
            currentCell?.status?.isOnlineBookable
                ?: samples.lastOrNull()?.available
                ?: false

        // Contiguous available runs across the seeded samples. A trailing open run
        // extends to `now` when the cell is currently open, so a stable-open cell
        // whose only edge predates the window reports a real duration, not ~0.
        data class Run(
            val start: OffsetDateTime,
            val end: OffsetDateTime,
        )
        val runs = mutableListOf<Run>()
        var runStart: OffsetDateTime? = null
        var lastTrueAt: OffsetDateTime? = null
        for (s in samples) {
            if (s.available) {
                if (runStart == null) runStart = s.observedAt
                lastTrueAt = s.observedAt
            } else if (runStart != null) {
                runs += Run(start = runStart, end = lastTrueAt!!)
                runStart = null
            }
        }
        if (runStart != null) {
            runs += Run(start = runStart, end = if (isCurrentlyOpen) maxOf(lastTrueAt!!, now) else lastTrueAt!!)
        }

        // Current open window prefers the cube's last_changed_at (the exact edge
        // that opened the current run), which may predate the seeded samples.
        val currentOrLastOpenWindowSec =
            if (isCurrentlyOpen && currentCell != null) {
                durationSec(currentCell.lastChangedAt, now)
            } else {
                runs.lastOrNull()?.let { durationSec(it.start, it.end) }
            }
        val medianOpenWindowSec = medianOrNull(runs.map { durationSec(it.start, it.end) })

        // Count false→true transitions in the last 24h; the seed supplies the
        // state entering the window so an entering flip is counted.
        var flips = 0
        var prev: Snapshot? = seed
        for (s in windowRows) {
            if (s.observedAt >= flipWindowStart && prev != null && !prev.available && s.available) {
                flips += 1
            }
            prev = s
        }
        val lastOpenAt = lastTrueAt ?: currentCell?.takeIf { isCurrentlyOpen }?.lastObservedAt
        return TargetDateStats(
            targetDate = date,
            totalSnapshots = windowRows.size,
            lastOpenAt = lastOpenAt,
            isCurrentlyOpen = isCurrentlyOpen,
            currentOrLastOpenWindowSec = currentOrLastOpenWindowSec,
            medianOpenWindowSec = medianOpenWindowSec,
            flipsLast24h = flips,
        )
    }

    private fun durationSec(
        start: OffsetDateTime,
        end: OffsetDateTime,
    ): Int =
        java.time.Duration
            .between(start, end)
            .seconds
            .toInt()
            .coerceAtLeast(0)

    private fun medianOrNull(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
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
