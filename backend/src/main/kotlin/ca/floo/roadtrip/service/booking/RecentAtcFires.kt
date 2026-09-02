package ca.floo.roadtrip.service.booking

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Profiles that have driven a browser for a hold in the last [window].
 *
 * The keepalive sweep and the fire path contend for the same per-profile lock on
 * the companion, and the sweep is the one that can afford to lose: a refresh it
 * skips costs a slightly staler session, while a fire it blocks costs the hold
 * outright — the POST comes back `profile_busy`, nothing retries inside the
 * seconds-critical window, and the site goes to somebody else.
 *
 * In memory on purpose, and best-effort by construction. The keepalive is
 * explicitly not load-bearing (see `RecGovKeepaliveJob`), so a restart that
 * forgets a fire costs one avoidable refresh, not a hold — which is not worth a
 * column, a migration and a write on the critical path.
 *
 * The window is the ATC run's own budget rather than a number of its own: a fire
 * holds the lock for as long as the companion may take to answer it.
 */
internal class RecentAtcFires(
    private val window: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val firedAt = ConcurrentHashMap<String, Instant>()

    fun record(profileId: String) {
        firedAt[profileId] = clock.instant()
    }

    /** True while [profileId]'s last fire may still be holding the profile lock. */
    fun firedRecently(profileId: String): Boolean {
        val at = firedAt[profileId] ?: return false
        if (Duration.between(at, clock.instant()) <= window) return true
        // Dropped on read, which only reaches profiles the sweep still asks
        // about: one that fires and then leaves the armed set keeps its entry.
        // That is bounded by the number of users who have ever fired, so it is
        // not worth a reaper — but it is not self-clearing either.
        firedAt.remove(profileId, at)
        return false
    }
}
