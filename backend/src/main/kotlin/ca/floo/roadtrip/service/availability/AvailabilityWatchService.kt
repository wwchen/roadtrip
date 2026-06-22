package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityJobRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo.Watch
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.scheduler.jobs.AvailabilityJobIntent
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.OffsetDateTime

/**
 * Mutates watches and keeps their backing job in sync. Single seam for
 * routes; routes never touch [AvailabilityWatchRepo] or
 * [AvailabilityJobRepo] for writes.
 *
 * All mutations transact across both tables so a watch is never visible
 * without its job.
 */
class AvailabilityWatchService(
    private val ctx: DSLContext,
    private val reservablesRepo: ReservableRepo,
) {
    private val parkedFar: OffsetDateTime = OffsetDateTime.parse("9999-01-01T00:00:00Z")

    fun create(input: AvailabilityWatchRepo.CreateInput): Watch =
        ctx.transactionResult { config ->
            val watchRepo = AvailabilityWatchRepo(DSL.using(config))
            val jobRepo = AvailabilityJobRepo(DSL.using(config))
            val watch = watchRepo.create(input)
            val intent = buildIntent(watch)
            val nextRun = if (watch.status == WatchStatus.ACTIVE) OffsetDateTime.now() else parkedFar
            jobRepo.upsertForWatch(
                watchId = watch.id,
                intentPayload = intent.toJsonObject(),
                cadenceSec = watch.cadenceSec,
                status = watch.status,
                nextRunAt = nextRun,
            )
            watch
        }

    fun update(
        id: Long,
        input: AvailabilityWatchRepo.UpdateInput,
    ): Watch? =
        ctx.transactionResult { config ->
            val watchRepo = AvailabilityWatchRepo(DSL.using(config))
            val jobRepo = AvailabilityJobRepo(DSL.using(config))
            val updated = watchRepo.update(id, input) ?: return@transactionResult null
            val intent = buildIntent(updated)
            val nextRun =
                when (updated.status) {
                    WatchStatus.ACTIVE -> {
                        // If the watch was just resumed, kick the next run
                        // to "now" so polling restarts on the next tick.
                        // For an in-place edit (already active), keep the
                        // existing schedule by reusing the job's nextRunAt
                        // when present; otherwise default to now.
                        val existing = jobRepo.findByWatchId(updated.id)
                        if (existing == null || existing.status != WatchStatus.ACTIVE || existing.nextRunAt == parkedFar) {
                            OffsetDateTime.now()
                        } else {
                            existing.nextRunAt
                        }
                    }
                    WatchStatus.PAUSED -> parkedFar
                    WatchStatus.DONE -> parkedFar
                }
            jobRepo.upsertForWatch(
                watchId = updated.id,
                intentPayload = intent.toJsonObject(),
                cadenceSec = updated.cadenceSec,
                status = updated.status,
                nextRunAt = nextRun,
            )
            updated
        }

    fun delete(id: Long): Boolean =
        ctx.transactionResult { config ->
            val watchRepo = AvailabilityWatchRepo(DSL.using(config))
            // FK cascade deletes the matching availability_job row.
            watchRepo.delete(id)
        }

    private fun buildIntent(watch: Watch): AvailabilityJobIntent =
        if (watch.reservableId != null) {
            val r =
                reservablesRepo.findById(watch.reservableId)
                    ?: error("watch ${watch.id} references missing reservable ${watch.reservableId}")
            AvailabilityJobIntent.Reservable(
                reservableId = r.id,
                reservableRid = r.rid.encode(),
                startDate = watch.startDate.toString(),
                endDate = watch.endDate.toString(),
            )
        } else {
            AvailabilityJobIntent.Poi(
                poiId = watch.poiId!!,
                reservableFilters = watch.reservableFilters,
                startDate = watch.startDate.toString(),
                endDate = watch.endDate.toString(),
            )
        }
}
