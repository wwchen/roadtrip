package ca.floo.roadtrip.service.scheduler.framework

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Result of one handler invocation. The handler returns the next
 * scheduling timestamp; the scheduler writes it back via
 * [SchedulableRepo.release] together with the run timestamp.
 *
 * Handlers should catch their own domain errors and return a usable
 * [HandlerResult] anyway; uncaught throwables are logged and the row's
 * lease is released with the original cadence so we don't lose the row.
 */
data class HandlerResult(
    val nextRunAt: OffsetDateTime,
)

/**
 * In-process scheduler. One instance per Schedulable type
 * (`Scheduler<Poller>` for availability polling, eventually
 * `Scheduler<IngestRun>` for ETLs).
 *
 * Owns: tick cadence, claim batch size, lease duration, boot recovery,
 * exception isolation. Does NOT own: domain logic (the handler) or
 * cadence math (handler returns next_run_at).
 */
class Scheduler<T : Schedulable>(
    private val repo: SchedulableRepo<T>,
    private val handler: suspend (T) -> HandlerResult,
    private val tickInterval: Duration = Duration.ofSeconds(5),
    private val claimBatchSize: Int = 10,
    private val leaseDuration: Duration = Duration.ofMinutes(2),
    private val name: String = "scheduler",
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger("Scheduler($name)")
    private var loop: Job? = null

    fun start(scope: CoroutineScope) {
        check(loop == null) { "scheduler already running" }
        log.info("scheduler {} starting (tick={}s, batch={}, lease={}s)", name, tickInterval.seconds, claimBatchSize, leaseDuration.seconds)
        // Recover before the first claim. If the app crashed mid-run, the
        // matching row's lease has not yet expired (we'd wait the full
        // lease duration); bumping reclaim here cuts that to zero.
        repo.reclaimExpired(now())
        loop = scope.launch { runLoop() }
    }

    suspend fun stop() {
        loop?.cancel()
        loop = null
    }

    private suspend fun runLoop() {
        while (currentScopeIsActive()) {
            try {
                val now = now()
                repo.reclaimExpired(now)
                val rows = repo.claimDue(now, claimBatchSize, leaseDuration)
                for (row in rows) {
                    runOne(row)
                }
            } catch (e: Exception) {
                log.error("scheduler tick failed: {}", e.message, e)
            }
            delay(tickInterval.toMillis())
        }
    }

    private suspend fun runOne(row: T) {
        val started = now()
        val result =
            try {
                handler(row)
            } catch (e: Exception) {
                log.error("handler failed for row id={}: {}", row.id, e.message, e)
                // Re-schedule with default cadence-of-failure: try again
                // after the lease window so we don't hot-loop on a broken
                // row. Concrete cadence is decided by the repo's row data;
                // here we just push it past the current lease.
                HandlerResult(nextRunAt = started.plus(leaseDuration))
            }
        // Release uses NonCancellable so a stop() during the handler
        // still flushes the schedule update. Otherwise a stop racing a
        // running handler would leave the row claimed until lease expiry.
        withContext(NonCancellable) {
            val token = row.claimToken
            if (token == null) {
                log.warn("row id={} had no claim token; cannot release", row.id)
                return@withContext
            }
            val released = repo.release(row.id, token, result.nextRunAt, started)
            if (!released) {
                log.warn("row id={} release rejected (token mismatch — lease was reclaimed)", row.id)
            }
        }
    }

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private suspend fun currentScopeIsActive(): Boolean = kotlin.coroutines.coroutineContext[Job]?.isActive ?: true
}
