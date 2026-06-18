package ca.floo.roadtrip.clients.cache

import ca.floo.roadtrip.clients.aspira.AspiraAvailability
import ca.floo.roadtrip.clients.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.clients.aspira.AspiraOccupancy
import ca.floo.roadtrip.config.ApiCacheEntity
import ca.floo.roadtrip.repo.NoopPersistentCache
import ca.floo.roadtrip.repo.PersistentCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
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
    private val ttl: Duration = ApiCacheEntity.ASPIRA_AVAILABILITY.defaultTtl,
    private val clock: Clock = Clock.systemUTC(),
    private val persistentCache: PersistentCache = NoopPersistentCache,
    private val json: Json = Json,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    constructor(
        client: AspiraAvailabilityClient,
        ttl: Duration = ApiCacheEntity.ASPIRA_AVAILABILITY.defaultTtl,
        clock: Clock = Clock.systemUTC(),
        persistentCache: PersistentCache = NoopPersistentCache,
        json: Json = Json,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(client::fetch, ttl, clock, persistentCache, json, scope)

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
        val persistentKey = key.persistentKey()
        val now = Instant.now(clock)

        // Drop any stale entry, but only if its deferred completed (otherwise
        // a slow ongoing fetch would race with the eviction).
        if (force) {
            entries.remove(key)
            persistentCache.delete(NAMESPACE, persistentKey)
        } else {
            val existing = entries[key]
            if (existing != null && existing.deferred.isCompleted) {
                val ageS = Duration.between(existing.fetchedAt, now).seconds
                if (ageS < ttl.seconds) {
                    val data = existing.deferred.await()
                    return CachedResult(
                        data = data,
                        hit = true,
                        ageSeconds = ageS,
                        ttlSeconds = ttl.seconds,
                        observedAt = existing.fetchedAt,
                    )
                }
                entries.remove(key, existing)
            }
        }

        if (!force) {
            val persisted = persistentCache.get(NAMESPACE, persistentKey)
            if (persisted != null) {
                try {
                    val data = json.decodeFromJsonElement(AspiraAvailability.serializer(), persisted.payload)
                    entries[key] = Entry(CompletableDeferred(data), persisted.createdAt)
                    return CachedResult(
                        data = data,
                        hit = true,
                        ageSeconds = persisted.ageSeconds(clock),
                        ttlSeconds = persisted.ttlSeconds(),
                        observedAt = persisted.createdAt,
                    )
                } catch (e: Exception) {
                    log.warn("aspira availability persistent cache decode failed key={}", persistentKey)
                    persistentCache.delete(NAMESPACE, persistentKey)
                }
            }
        }

        var createdFresh = false
        val entry =
            entries.computeIfAbsent(key) {
                createdFresh = true
                log.debug("aspira fetch start host={} mapId={}", host, mapId)
                Entry(
                    deferred = ownedScope.async { fetcher(host, mapId, startDate, endDate) },
                    fetchedAt = now,
                )
            }
        val data = entry.deferred.await()
        val ageSeconds = Duration.between(entry.fetchedAt, Instant.now(clock)).seconds
        if (createdFresh) {
            persistentCache.put(
                NAMESPACE,
                persistentKey,
                json.encodeToJsonElement(AspiraAvailability.serializer(), data),
                ttl,
            )
        }
        return CachedResult(
            data = data,
            hit = !createdFresh,
            ageSeconds = ageSeconds,
            ttlSeconds = ttl.seconds,
            observedAt = entry.fetchedAt,
        )
    }

    companion object {
        private val NAMESPACE = ApiCacheEntity.ASPIRA_AVAILABILITY.namespace
    }
}

private fun CachedAspiraAvailability.Key.persistentKey(): String =
    listOf(host, mapId.toString(), startDate.toString(), endDate.toString()).joinToString(":")

data class CachedResult(
    val data: AspiraAvailability,
    val hit: Boolean,
    val ageSeconds: Long,
    val ttlSeconds: Long,
    val observedAt: Instant,
)

class CachedAspiraOccupancy(
    private val fetcher: suspend (host: String, resourceLocationId: Int, startDate: LocalDate, endDate: LocalDate) -> AspiraOccupancy,
    private val ttl: Duration = ApiCacheEntity.ASPIRA_AVAILABILITY.defaultTtl,
    private val clock: Clock = Clock.systemUTC(),
    private val persistentCache: PersistentCache = NoopPersistentCache,
    private val json: Json = Json,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    constructor(
        client: AspiraAvailabilityClient,
        ttl: Duration = ApiCacheEntity.ASPIRA_AVAILABILITY.defaultTtl,
        clock: Clock = Clock.systemUTC(),
        persistentCache: PersistentCache = NoopPersistentCache,
        json: Json = Json,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(client::fetchOccupancy, ttl, clock, persistentCache, json, scope)

    private val log = LoggerFactory.getLogger(javaClass)
    private val ownedScope = scope

    data class Key(
        val host: String,
        val resourceLocationId: Int,
        val startDate: LocalDate,
        val endDate: LocalDate,
    )

    private data class Entry(
        val deferred: Deferred<AspiraOccupancy>,
        val fetchedAt: Instant,
    )

    private val entries = ConcurrentHashMap<Key, Entry>()

    suspend fun get(
        host: String,
        resourceLocationId: Int,
        startDate: LocalDate,
        endDate: LocalDate,
        force: Boolean = false,
    ): CachedOccupancyResult {
        val key = Key(host, resourceLocationId, startDate, endDate)
        val persistentKey = key.persistentKey()
        val now = Instant.now(clock)

        if (force) {
            entries.remove(key)
            persistentCache.delete(OCCUPANCY_NAMESPACE, persistentKey)
        } else {
            val existing = entries[key]
            if (existing != null && existing.deferred.isCompleted) {
                val ageS = Duration.between(existing.fetchedAt, now).seconds
                if (ageS < ttl.seconds) {
                    return CachedOccupancyResult(
                        data = existing.deferred.await(),
                        hit = true,
                        ageSeconds = ageS,
                        ttlSeconds = ttl.seconds,
                        observedAt = existing.fetchedAt,
                    )
                }
                entries.remove(key, existing)
            }
        }

        if (!force) {
            val persisted = persistentCache.get(OCCUPANCY_NAMESPACE, persistentKey)
            if (persisted != null) {
                try {
                    val data = json.decodeFromJsonElement(AspiraOccupancy.serializer(), persisted.payload)
                    entries[key] = Entry(CompletableDeferred(data), persisted.createdAt)
                    return CachedOccupancyResult(
                        data = data,
                        hit = true,
                        ageSeconds = persisted.ageSeconds(clock),
                        ttlSeconds = persisted.ttlSeconds(),
                        observedAt = persisted.createdAt,
                    )
                } catch (e: Exception) {
                    log.warn("aspira occupancy persistent cache decode failed key={}", persistentKey)
                    persistentCache.delete(OCCUPANCY_NAMESPACE, persistentKey)
                }
            }
        }

        var createdFresh = false
        val entry =
            entries.computeIfAbsent(key) {
                createdFresh = true
                log.debug("aspira occupancy fetch start host={} resourceLocationId={}", host, resourceLocationId)
                Entry(
                    deferred = ownedScope.async { fetcher(host, resourceLocationId, startDate, endDate) },
                    fetchedAt = now,
                )
            }
        val data = entry.deferred.await()
        val ageSeconds = Duration.between(entry.fetchedAt, Instant.now(clock)).seconds
        if (createdFresh) {
            persistentCache.put(
                OCCUPANCY_NAMESPACE,
                persistentKey,
                json.encodeToJsonElement(AspiraOccupancy.serializer(), data),
                ttl,
            )
        }
        return CachedOccupancyResult(
            data = data,
            hit = !createdFresh,
            ageSeconds = ageSeconds,
            ttlSeconds = ttl.seconds,
            observedAt = entry.fetchedAt,
        )
    }

    companion object {
        private const val OCCUPANCY_NAMESPACE = "aspira_occupancy"
    }
}

private fun CachedAspiraOccupancy.Key.persistentKey(): String =
    listOf(host, resourceLocationId.toString(), startDate.toString(), endDate.toString()).joinToString(":")

data class CachedOccupancyResult(
    val data: AspiraOccupancy,
    val hit: Boolean,
    val ageSeconds: Long,
    val ttlSeconds: Long,
    val observedAt: Instant,
)
