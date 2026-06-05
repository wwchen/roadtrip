package ca.floo.campsite.recgov.booker.availability

import ca.floo.campsite.recgov.booker.poller.AvailabilityClient
import ca.floo.campsite.recgov.booker.poller.Campsite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory TTL cache layered over [AvailabilityClient.fetchMonth]. Coalesces
 * concurrent waiters via [Deferred] so N pin clicks on the same campground
 * within the 1.5s rec.gov throttle window collapse to one outbound call.
 *
 * Cache key is `(provider, campgroundId, month)`. Provider is included from
 * day 1 so future state-park / Parks Canada / provincial integrations slot in
 * without rewriting the cache.
 *
 * The poller continues to call [AvailabilityClient.fetchMonth] directly — its
 * freshness semantics differ from the public route (poller is on its own
 * cadence; the route caches between map clicks). This wrapper is route-only.
 */
class CachedAvailability(
    private val fetchMonth: suspend (campgroundId: String, month: String) -> Map<String, Campsite>,
    private val ttl: Duration = Duration.ofMinutes(10),
    private val clock: Clock = Clock.systemUTC(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** Convenience constructor wired to a real [AvailabilityClient]. */
    constructor(
        client: AvailabilityClient,
        ttl: Duration = Duration.ofMinutes(10),
        clock: Clock = Clock.systemUTC(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(client::fetchMonth, ttl, clock, scope)

    private val log = LoggerFactory.getLogger(CachedAvailability::class.java)
    private val ownedScope = scope

    data class Key(
        val provider: String,
        val campgroundId: String,
        val month: String,
    )

    private data class Entry(
        val deferred: Deferred<Map<String, Campsite>>,
        val storedAt: Instant,
    )

    private val cache = ConcurrentHashMap<Key, Entry>()

    /**
     * Get cached month or fetch + cache. Concurrent callers for the same key
     * receive the same in-flight [Deferred]. On failure, evict so the next
     * caller retries.
     *
     * @param force when true, bypass cache (used by the Refresh affordance).
     */
    suspend fun get(
        provider: String,
        campgroundId: String,
        month: String,
        force: Boolean = false,
    ): CachedResult {
        val key = Key(provider, campgroundId, month)
        val now = Instant.now(clock)
        if (force) cache.remove(key)

        val existing = cache[key]
        if (existing != null) {
            val age = Duration.between(existing.storedAt, now)
            if (age < ttl) {
                return try {
                    val data = existing.deferred.await()
                    CachedResult(data, hit = true, ageSeconds = age.seconds, ttlSeconds = ttl.seconds)
                } catch (t: Throwable) {
                    cache.remove(key, existing)
                    throw t
                }
            }
            // expired — fall through to refresh
            cache.remove(key, existing)
        }

        // Coalesce: only one fetch in flight per key. computeIfAbsent guards
        // the put under a hash-bucket lock; concurrent callers either install
        // their own deferred or read the existing one.
        val entry =
            cache.computeIfAbsent(key) {
                Entry(
                    deferred = ownedScope.async { fetchMonth(campgroundId, month) },
                    storedAt = now,
                )
            }

        return try {
            val data = entry.deferred.await()
            CachedResult(data, hit = false, ageSeconds = 0, ttlSeconds = ttl.seconds)
        } catch (t: Throwable) {
            cache.remove(key, entry)
            throw t
        }
    }

    /** Drop everything. Useful in tests. */
    fun clear() = cache.clear()

    fun size(): Int = cache.size
}

data class CachedResult(
    val data: Map<String, Campsite>,
    val hit: Boolean,
    val ageSeconds: Long,
    val ttlSeconds: Long,
)
