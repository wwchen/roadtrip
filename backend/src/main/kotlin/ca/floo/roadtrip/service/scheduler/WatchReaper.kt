package ca.floo.roadtrip.service.scheduler

import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration

/** How often the reaper sweeps for elapsed watches. */
private val DEFAULT_REAP_INTERVAL: Duration = Duration.ofMinutes(5)

/**
 * Owns elapsed-watch teardown, the one lifecycle concern the poll tick used to
 * carry. A watch elapses with no user action (its `end_date` simply passes), so
 * something must notice; that is this sweep, not the executor.
 *
 * Correctness never depends on the reaper running promptly: liveness is a
 * *derived predicate* (`status = ACTIVE AND end_date >= today`, see
 * [AvailabilityPollerRepo.liveWatchesForPoller]), so an elapsed watch already
 * drops out of every live query the instant its date passes. The reaper only
 * *materializes* that — flips the watch to `done` for the UI, drops its poller
 * link, and lets the "zero links -> dormant" rule stop the poller. Until it
 * runs, an all-elapsed poller simply skips its ticks (the executor makes no
 * upstream call when it has no live watches).
 *
 * A lightweight periodic loop rather than a [framework.Scheduler]: the unit of
 * work is a single global sweep on an interval, not a per-row claim/lease.
 */
internal class WatchReaper(
    private val pollers: AvailabilityPollerRepo,
    private val interval: Duration = DEFAULT_REAP_INTERVAL,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var loop: Job? = null

    fun start(scope: CoroutineScope) {
        check(loop == null) { "reaper already running" }
        log.info("watch reaper starting (interval={}s)", interval.seconds)
        loop = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        while (isActive()) {
            sweepOnce()
            delay(interval.toMillis())
        }
    }

    /** One sweep. Public so tests (and a future admin trigger) can run it
     *  deterministically without the loop. */
    fun sweepOnce() {
        try {
            val outcome = pollers.reapElapsedWatches()
            if (outcome.reapedWatchIds.isNotEmpty()) {
                // Audit event: the exact lifecycle transitions this sweep made,
                // by id — an elapsed watch completing (-> done) and a poller going dormant
                // are the state changes worth an audit trail, not just a count.
                log.info(
                    "watch-reaper audit: completed elapsed watches {} (-> done); deactivated pollers {}",
                    outcome.reapedWatchIds,
                    outcome.deactivatedPollerIds,
                )
            }
        } catch (e: Exception) {
            log.error("watch reaper sweep failed: {}", e.message, e)
        }
    }

    private suspend fun isActive(): Boolean = kotlin.coroutines.coroutineContext[Job]?.isActive ?: true
}
