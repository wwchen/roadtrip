package ca.floo.roadtrip.service.scheduler

import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.observability.KeepaliveOutcome
import ca.floo.roadtrip.observability.RoadtripMetrics
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.availability.AvailabilityTriggerKinds
import ca.floo.roadtrip.service.availability.WatchStatus
import ca.floo.roadtrip.service.settings.CompanionActionResult
import ca.floo.roadtrip.service.settings.CompanionSessionPort
import ca.floo.roadtrip.service.settings.RecGovProfileSessionPort
import ca.floo.roadtrip.service.settings.RecGovSessionCodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Keeps the browser profiles behind armed `atc` watches warm.
 *
 * An ATC fires inside a seconds-critical window — the site is gone by the time
 * a cold Chromium has launched and logged in. So the profiles that *might* fire
 * are kept launched and their rec.gov sessions renewed ahead of time, and the
 * fire path's own re-login stays the exception rather than the norm.
 *
 * Each sweep does two things, in this order:
 *
 *  1. **Pushes the armed set** — the distinct owners of active `atc` watches —
 *     to the companion, wholesale. The companion cannot derive this (the
 *     watches are ours), and replacing rather than merging is what disarms a
 *     profile whose last `atc` watch was paused or deleted. This runs even when
 *     the set is empty, which is exactly when disarming matters.
 *  2. **Refreshes each armed profile**, sequentially. Parallel refreshes would
 *     race the companion's concurrent-browser cap and its per-profile locks for
 *     no gain — this is a background sweep with a whole cadence to finish in.
 *
 * A lightweight periodic loop rather than a [framework.Scheduler], following
 * [WatchReaper]: the unit of work is one global sweep on an interval, not a
 * per-row claim and lease. Nothing here is load-bearing for correctness — a
 * missed sweep costs a cold start (or an unattended re-login) on the next fire,
 * not a missed hold.
 */
internal class RecGovKeepaliveJob(
    private val watchRepo: AvailabilityWatchRepo,
    private val companion: CompanionSessionPort,
    /** Owns the user-id → profile-id mapping, so it is not restated here. */
    private val profiles: RecGovProfileSessionPort,
    private val metrics: RoadtripMetrics,
    private val interval: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var loop: Job? = null

    fun start(scope: CoroutineScope) {
        check(loop == null) { "recgov keepalive already running" }
        log.info("recgov keepalive starting (interval={}s)", interval.seconds)
        loop = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        while (isActive()) {
            sweepOnce()
            delay(interval.toMillis())
        }
    }

    /** One sweep. Public so tests can run it without the loop. */
    suspend fun sweepOnce() {
        val armed =
            try {
                armedProfileIds()
            } catch (e: Exception) {
                log.error("recgov keepalive could not read the armed watch set: {}", e.message, e)
                return
            }

        when (val marked = companion.markKeepWarm(armed)) {
            is CompanionActionResult.Ok ->
                log.info("recgov keepalive armed {} profile(s)", armed.size)
            is CompanionActionResult.Failed -> {
                // Nothing is refreshed after a failed mark: an unreachable
                // companion would only produce one unavailable count per armed
                // profile, and a companion that rejected the mark has not
                // launched them either.
                log.warn("recgov keepalive could not push the armed set: {} {}", marked.code, marked.detail)
                armed.forEach { metrics.recgovKeepaliveProfile(outcomeFor(marked.code)) }
                return
            }
        }

        armed.forEach { profileId -> refresh(profileId) }
    }

    private suspend fun refresh(profileId: String) {
        val outcome =
            when (val result = companion.refresh(profileId)) {
                is CompanionActionResult.Ok -> KeepaliveOutcome.REFRESHED
                is CompanionActionResult.Failed -> {
                    log.info(
                        "recgov keepalive refresh declined for profile={} code={} detail={}",
                        profileId,
                        result.code,
                        result.detail,
                    )
                    outcomeFor(result.code)
                }
            }
        metrics.recgovKeepaliveProfile(outcome)
    }

    /**
     * One armed profile per *owner*, not per watch: five `atc` watches for one
     * user are still one browser profile.
     */
    private fun armedProfileIds(): List<String> =
        watchRepo
            .distinctOwnersByTriggerKind(WatchStatus.ACTIVE, AvailabilityTriggerKinds.ATC)
            .map { profiles.profileId(UserId(it)) }

    private fun outcomeFor(code: String): KeepaliveOutcome =
        if (code == RecGovSessionCodes.COMPANION_UNAVAILABLE) KeepaliveOutcome.UNAVAILABLE else KeepaliveOutcome.FAILED

    private suspend fun isActive(): Boolean = kotlin.coroutines.coroutineContext[Job]?.isActive ?: true
}

/**
 * Koin holder for the optional keepalive job.
 *
 * A `single { }` that produces null throws at resolution, and a deployment
 * without a companion has no profiles to keep warm — the same optional-dependency
 * shape the settings service uses.
 */
internal class RecGovKeepalive(
    val job: RecGovKeepaliveJob?,
)
