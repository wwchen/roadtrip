package ca.floo.campsite.recgov.booker.scheduler

import ca.floo.campsite.recgov.booker.db.Schedule
import ca.floo.campsite.recgov.booker.events.CampsiteEvent
import ca.floo.campsite.recgov.booker.events.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Declarative event scheduler. The scheduler does no business logic — it
 * reads enabled rows from `schedules` plus active rows from `alerts` and
 * launches one coroutine job per row that publishes its event on the [bus]
 * at the configured cadence. Lifecycle managers (TokenManager,
 * AvailabilityManager, ...) subscribe and run the work.
 *
 * Sources of jobs:
 *   - [ScheduleRepo.listEnabled] — system schedules (token refresh, lease
 *     sweep, companion sweep, liveness tick).
 *   - [AlertRepo.listActiveCadences] — per-alert poll cadences. One
 *     `PollDue(alertId)` job per active alert.
 *
 * Restart semantics: declarative. We do NOT persist next-fire timestamps.
 * Each job starts with `delay(jitter(0..cadence))` to avoid a thundering
 * herd on deploy.
 *
 * Reload: explicit. Routes that mutate schedule rows or alert state
 * call [reload] / [upsertAlert] / [removeAlert] — no DB watcher.
 */
class Scheduler(
    private val listSchedules: () -> List<Schedule>,
    private val listAlertCadences: () -> List<Pair<Long, Int>>,
    private val bus: EventBus,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(Scheduler::class.java)

    /** name → job for system schedules. */
    private val systemJobs = ConcurrentHashMap<String, Job>()

    /** alertId → job for per-alert poll cadences. */
    private val alertJobs = ConcurrentHashMap<Long, Job>()

    fun start() {
        reload()
    }

    fun stop() {
        systemJobs.values.forEach { it.cancel() }
        systemJobs.clear()
        alertJobs.values.forEach { it.cancel() }
        alertJobs.clear()
    }

    /** Cancel and re-launch every job. Cheap for our scale (a handful of system schedules + N alerts). */
    fun reload() {
        // System schedules
        val wantedSystem = listSchedules().associateBy { it.name }
        // Drop disabled / removed
        for (name in systemJobs.keys.toList() - wantedSystem.keys) {
            systemJobs.remove(name)?.cancel()
        }
        // (Re)launch wanted
        for ((name, sched) in wantedSystem) {
            systemJobs[name]?.cancel()
            systemJobs[name] = launchSchedule(sched)
        }

        // Per-alert poll cadences
        val wantedAlerts = listAlertCadences().toMap()
        for (alertId in alertJobs.keys.toList() - wantedAlerts.keys) {
            alertJobs.remove(alertId)?.cancel()
        }
        for ((alertId, cadence) in wantedAlerts) {
            alertJobs[alertId]?.cancel()
            alertJobs[alertId] = launchAlertJob(alertId, cadence)
        }
        log.info("Scheduler reloaded: {} system schedule(s), {} alert poll job(s)", systemJobs.size, alertJobs.size)
    }

    /** Targeted variant of [reload] for a single alert (cheaper than full reload). */
    fun upsertAlert(alertId: Long) {
        val cadence = listAlertCadences().firstOrNull { it.first == alertId }?.second
        alertJobs.remove(alertId)?.cancel()
        if (cadence != null) {
            alertJobs[alertId] = launchAlertJob(alertId, cadence)
            log.info("Scheduler: alert {} poll job (re)started @ {}s", alertId, cadence)
        } else {
            log.info("Scheduler: alert {} no longer active, removed poll job", alertId)
        }
    }

    fun removeAlert(alertId: Long) {
        alertJobs.remove(alertId)?.cancel()?.let {
            log.info("Scheduler: alert {} poll job cancelled", alertId)
        }
    }

    private fun launchSchedule(sched: Schedule): Job =
        scope.launch {
            // Startup jitter so all jobs don't fire at t=0 after a deploy.
            delay(Random.nextLong(0, sched.cadenceSec.toLong()).seconds)
            while (isActive) {
                runCatching { bus.publish(systemEventFor(sched)) }
                    .onFailure { log.error("Schedule '{}' publish failed: {}", sched.name, it.message) }
                delay(sched.cadenceSec.seconds)
            }
        }

    private fun launchAlertJob(
        alertId: Long,
        cadenceSec: Int,
    ): Job =
        scope.launch {
            delay(Random.nextLong(0, cadenceSec.toLong()).seconds)
            while (isActive) {
                runCatching { bus.publish(CampsiteEvent.PollDue(alertId)) }
                    .onFailure { log.error("Alert {} PollDue publish failed: {}", alertId, it.message) }
                delay(cadenceSec.seconds)
            }
        }

    /**
     * Maps a schedule row's [Schedule.eventType] string to a concrete
     * [CampsiteEvent] singleton. Unknown types fall through to a logged
     * warning + [CampsiteEvent.LivenessTick] as a safe no-op.
     */
    private fun systemEventFor(sched: Schedule): CampsiteEvent =
        when (sched.eventType) {
            "TokenRefreshDue" -> CampsiteEvent.TokenRefreshDue
            "LeaseSweepDue" -> CampsiteEvent.LeaseSweepDue
            "CompanionSweepDue" -> CampsiteEvent.CompanionSweepDue
            "LivenessTick" -> CampsiteEvent.LivenessTick
            else -> {
                log.warn("Unknown schedule event_type='{}' for schedule '{}', skipping", sched.eventType, sched.name)
                CampsiteEvent.LivenessTick
            }
        }
}
