package ca.floo.roadtrip.repo

import ca.floo.roadtrip.client.AspiraAvailability
import ca.floo.roadtrip.client.AspiraAvailabilityClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory TTL cache layered over [AspiraAvailabilityClient.fetch]. Coalesces
 * concurrent waiters via [Deferred] so N pin clicks on the same campground
 * within the throttle window collapse to one outbound call.
 *
 * Cache key is `(host, mapId, startDate, endDate)`. Most drawer fetches use
 * a `today..today+30d` window, so the date pair effectively becomes a
 * daily cache key.
 */
class CachedAspiraAvailability(
    private val fetcher: suspend (host: String, mapId: Int, startDate: LocalDate, endDate: LocalDate) -> AspiraAvailability,
    private val ttl: Duration = Duration.ofHours(2),
    private val clock: Clock = Clock.systemUTC(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    constructor(
        client: AspiraAvailabilityClient,
        ttl: Duration = Duration.ofHours(2),
        clock: Clock = Clock.systemUTC(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(client::fetch, ttl, clock, scope)

    private val log = LoggerFactory.getLogger(javaClass)
    private val ownedScope = scope

    data class Key(
        val host: String,
        val mapId: Int,
        val startDate: LocalDate,
        val endDate: LocalDate,
    )

    private data class Entry(
        val deferred: Deferred<AspiraAvailability>,
        val fetchedAt: Instant,
    )

    private val entries = ConcurrentHashMap<Key, Entry>()

    suspend fun get(
        host: String,
        mapId: Int,
        startDate: LocalDate,
        endDate: LocalDate,
        force: Boolean = false,
    ): CachedResult {
        val key = Key(host, mapId, startDate, endDate)
        val now = Instant.now(clock)

        // Drop any stale entry, but only if its deferred completed (otherwise
        // a slow ongoing fetch would race with the eviction).
        if (force) {
            entries.remove(key)
        } else {
            val existing = entries[key]
            if (existing != null && existing.deferred.isCompleted) {
                val ageS = Duration.between(existing.fetchedAt, now).seconds
                if (ageS >= ttl.seconds) entries.remove(key, existing)
            }
        }

        val entry =
            entries.computeIfAbsent(key) {
                log.debug("aspira fetch start host={} mapId={}", host, mapId)
                Entry(
                    deferred = ownedScope.async { fetcher(host, mapId, startDate, endDate) },
                    fetchedAt = now,
                )
            }
        val data = entry.deferred.await()
        val ageSeconds = Duration.between(entry.fetchedAt, Instant.now(clock)).seconds
        return CachedResult(
            data = data,
            hit = ageSeconds > 0, // first call returns hit=false; subsequent within TTL are hit=true
            ageSeconds = ageSeconds,
            ttlSeconds = ttl.seconds,
        )
    }
}

data class CachedResult(
    val data: AspiraAvailability,
    val hit: Boolean,
    val ageSeconds: Long,
    val ttlSeconds: Long,
)
