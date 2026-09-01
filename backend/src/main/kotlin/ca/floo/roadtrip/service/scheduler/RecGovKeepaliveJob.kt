package ca.floo.roadtrip.service.scheduler

import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.observability.KeepaliveOutcome
import ca.floo.roadtrip.observability.RoadtripMetrics
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.availability.AvailabilityTriggerKinds
import ca.floo.roadtrip.service.availability.WatchStatus
import ca.floo.roadtrip.service.settings.CompanionActionResult
import ca.floo.roadtrip.service.settings.CompanionSessionHealth
import ca.floo.roadtrip.service.settings.CompanionSessionPort
import ca.floo.roadtrip.service.settings.RecGovProfileSessionPort
import ca.floo.roadtrip.service.settings.RecGovSessionCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration

/** Every user with rec.gov credentials stored. */
internal fun interface RecGovCredentialedUsers {
    fun userIdsWithRecgovCredentials(): List<Long>
}

/**
 * How many profiles may be kept warm at once.
 *
 * Comfortably above one, and above the companion's own default browser cap:
 * armed profiles are exempt from that cap, so this is the bound on how many
 * sessions we are willing to *ask* it to hold, not on how many it will launch.
 */
internal const val DEFAULT_MAX_KEEP_WARM_PROFILES = 25

/**
 * Keeps warm the browser profiles worth keeping warm.
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
    /** Who has credentials stored — the widened half of the keep-warm set. */
    private val credentials: RecGovCredentialedUsers,
    private val metrics: RoadtripMetrics,
    private val interval: Duration,
    private val maxProfiles: Int = DEFAULT_MAX_KEEP_WARM_PROFILES,
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

    /**
     * One sweep. Public so tests can run it without the loop.
     *
     * Guarded whole, following [WatchReaper]: this runs on a bare `launch`, so
     * anything thrown here would end the loop for the process lifetime and
     * leave every later fire paying a cold start, silently.
     */
    suspend fun sweepOnce() {
        try {
            sweep()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("recgov keepalive sweep failed: {}", e.message, e)
        }
    }

    private suspend fun sweep() {
        val armed = keepWarmProfileIds()

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

        armed.forEach { profileId -> refreshGuarded(profileId) }
    }

    /**
     * One profile's refresh, guarded separately: a sweep is best-effort, so one
     * profile's failure must not cost the remaining profiles theirs.
     */
    private suspend fun refreshGuarded(profileId: String) {
        try {
            refresh(profileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("recgov keepalive refresh failed for profile={}: {}", profileId, e.message, e)
            metrics.recgovKeepaliveProfile(KeepaliveOutcome.FAILED)
        }
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
     * Who to keep warm: armed watches **plus** anyone signed in with credentials.
     *
     * Armed-only was too narrow, and it showed up as the bug this widening
     * fixes: a user who logged in headed had their session lapse ~30 minutes
     * later because they had no active `atc` watch, so nothing refreshed them,
     * and Test login then walked into the bot wall. A session is worth keeping
     * alive for anyone who established one — the whole point is that a live
     * session cannot be re-established cheaply once it dies.
     *
     * Bounded, in this order:
     *
     *  1. **Armed profiles first.** They have a watch that may fire in seconds;
     *     everyone else merely has a session worth not losing.
     *  2. Credentialed users whose profile the companion has actually signed in
     *     at some point. `NeverLoggedIn` is skipped — there is no session to
     *     keep alive, and launching a browser to discover that again every
     *     cadence is pure cost.
     *  3. Truncated to [maxProfiles], so a large user base cannot ask the
     *     companion for more resident browsers than it can hold.
     */
    private suspend fun keepWarmProfileIds(): List<String> {
        val armed =
            watchRepo
                .distinctOwnersByTriggerKind(WatchStatus.ACTIVE, AvailabilityTriggerKinds.ATC)
                .map(::UserId)
        val armedIds = armed.map { it.value }.toSet()
        val credentialed = credentials.userIdsWithRecgovCredentials().filterNot { it in armedIds }.map(::UserId)

        val ordered = armed + credentialed.filter { hasSessionWorthKeeping(it) }
        if (ordered.size > maxProfiles) {
            log.info("recgov keepalive set truncated to {} of {} eligible profiles", maxProfiles, ordered.size)
        }
        return ordered.take(maxProfiles).map(profiles::profileId)
    }

    /**
     * Whether this profile has ever been signed in.
     *
     * An unreachable companion answers "yes": the alternative is silently
     * dropping every profile from the armed set during a blip, which is exactly
     * when losing sessions hurts most. The refresh below will report the
     * outage itself.
     */
    private suspend fun hasSessionWorthKeeping(userId: UserId): Boolean = profiles.health(userId) !is CompanionSessionHealth.NeverLoggedIn

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
