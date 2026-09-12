package ca.floo.roadtrip.service.scheduler

import ca.floo.roadtrip.model.availability.WatchStatus
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.UnitOfWork
import ca.floo.roadtrip.service.availability.AvailabilityPollerMembership
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

/** Cap on how many active watches a single backfill pass will consider. */
private const val BACKFILL_BATCH_LIMIT = 500

/**
 * One-time, idempotent boot backfill that fills poller links for any ACTIVE
 * watch that currently has none. Runs after Flyway and before the scheduler
 * starts. Idempotent: a watch that already has links is skipped, so a second
 * run (or a fresh boot with everything already linked) is a no-op.
 *
 * This is the bridge from the pre-cutover world (per-watch jobs, now dropped)
 * to pollers: after V28 drops `availability_job`, existing active watches have
 * no poller membership until their next mutation. Backfill links them at boot
 * so polling resumes without waiting for an edit.
 */
internal class PollerBackfill(
    private val watchRepo: AvailabilityWatchRepo,
    private val pollerRepo: AvailabilityPollerRepo,
    private val unitOfWork: UnitOfWork,
    private val membership: AvailabilityPollerMembership,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run() {
        val active = watchRepo.list(status = WatchStatus.ACTIVE, limit = BACKFILL_BATCH_LIMIT)
        if (active.size == BACKFILL_BATCH_LIMIT) {
            // We hit the cap: any active watches beyond this page get no poller
            // membership until their next mutation. Surface the gap instead of
            // silently under-linking. (Paginate here if this ever fires in prod.)
            log.warn("poller backfill hit the {}-watch cap; watches beyond it stay unlinked until edited", BACKFILL_BATCH_LIMIT)
        }
        var filled = 0
        for (w in active) {
            if (pollerRepo.pollerIdsForWatch(w.id).isNotEmpty()) continue
            unitOfWork.run { repos ->
                membership.sync(w, repos.pollers, tighterCadencePull = OffsetDateTime.now())
            }
            filled++
        }
        if (filled > 0) log.info("poller backfill linked {} active watches", filled)
    }
}
