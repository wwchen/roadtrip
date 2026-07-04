package ca.floo.roadtrip.repo

import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate

/**
 * Read/write port for the live availability cache path
 * ([ca.floo.roadtrip.service.api.SnapshotBackedAvailabilityService]).
 *
 * Latest state is read from the `availability_cell` cube — one indexed row per
 * (reservable, date) — NOT via `DISTINCT ON` over the append-only
 * `availability_snapshot` history. The history read scanned all rows per cell
 * and spilled to an on-disk sort (≈5.6s for a 235-reservable POI, growing with
 * history size); the cube read is a point lookup (≈5ms). See the
 * fix/availability-read-from-cube investigation.
 *
 * Writes mirror the poller's `writeCube`: upsert the cube (which bumps
 * liveness and detects status edges) and append ONLY edge rows to the
 * snapshot log, in one transaction. This keeps the on-demand read path and the
 * background poller writing the same two tables the same way, and stops the
 * snapshot log from growing on every read (the old path appended a full
 * cell-set unconditionally, which is why it reached millions of rows).
 */
interface AvailabilityCacheStore {
    /** Current cell per (reservable, date) from the cube. Missing pairs absent. */
    fun loadLatest(
        reservableIds: List<Long>,
        dates: List<LocalDate>,
    ): List<AvailabilitySnapshotRepo.LatestObservation>

    /**
     * Upsert [observations] into the cube and append the status-changed subset
     * to the snapshot log, transactionally. [reservableRidByDbId] supplies the
     * encoded rid stored on snapshot rows.
     */
    fun recordFetched(
        runId: Long?,
        observations: List<AvailabilityCellRepo.CellObservation>,
        reservableRidByDbId: Map<Long, String>,
    )
}

class AvailabilityCacheStoreImpl(
    private val ctx: DSLContext,
) : AvailabilityCacheStore {
    override fun loadLatest(
        reservableIds: List<Long>,
        dates: List<LocalDate>,
    ): List<AvailabilitySnapshotRepo.LatestObservation> =
        AvailabilityCellRepo(ctx)
            .loadCells(reservableIds, dates)
            .map { cell ->
                AvailabilitySnapshotRepo.LatestObservation(
                    reservableId = cell.reservableId,
                    targetDate = cell.targetDate,
                    observedAt = cell.lastObservedAt,
                    status = cell.status,
                    available = cell.status.isOnlineBookable,
                )
            }

    override fun recordFetched(
        runId: Long?,
        observations: List<AvailabilityCellRepo.CellObservation>,
        reservableRidByDbId: Map<Long, String>,
    ) {
        if (observations.isEmpty()) return
        val observedAtByKey = observations.associate { (it.reservableId to it.targetDate) to it.observedAt }
        ctx.transaction { config ->
            val txn = DSL.using(config)
            val changed =
                AvailabilityCellRepo(txn)
                    .upsertObservations(observations)
                    .filter { it.changed }
            if (changed.isEmpty()) return@transaction
            AvailabilitySnapshotRepo(txn).appendObservations(
                AvailabilitySnapshotRepo.SnapshotObservationBatch(
                    runId = runId,
                    observations =
                        changed.map { result ->
                            AvailabilitySnapshotRepo.SnapshotObservation(
                                reservableId = result.reservableId,
                                reservableRid = reservableRidByDbId[result.reservableId],
                                targetDate = result.targetDate,
                                observedAt = observedAtByKey[result.reservableId to result.targetDate]!!,
                                status = result.status,
                            )
                        },
                ),
            )
        }
    }
}
