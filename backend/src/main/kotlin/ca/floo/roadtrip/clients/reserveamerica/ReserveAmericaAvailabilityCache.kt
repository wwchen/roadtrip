package ca.floo.roadtrip.clients.reserveamerica

import ca.floo.roadtrip.config.ApiCacheEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

class CachedReserveAmericaAvailability(
    private val client: ReserveAmericaAvailabilityClient,
    private val ttl: Duration = ApiCacheEntity.RESERVEAMERICA_AVAILABILITY.defaultTtl,
    private val clock: Clock = Clock.systemUTC(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val ownedScope = scope

    data class Key(
        val contractCode: String,
        val parkId: String,
        val startDate: LocalDate,
        val endDate: LocalDate,
    )

    private data class Entry(
        val deferred: Deferred<ReserveAmericaAvailability>,
        val storedAt: Instant,
    )

    private val cache = ConcurrentHashMap<Key, Entry>()

    suspend fun get(
        contractCode: String,
        parkId: String,
        startDate: LocalDate,
        endDate: LocalDate,
        force: Boolean = false,
    ): CachedReserveAmericaResult {
        val key = Key(contractCode, parkId, startDate, endDate)
        val now = Instant.now(clock)
        if (force) cache.remove(key)

        val existing = cache[key]
        if (existing != null) {
            val age = Duration.between(existing.storedAt, now)
            if (age < ttl) {
                return try {
                    CachedReserveAmericaResult(
                        data = existing.deferred.await(),
                        hit = true,
                        ageSeconds = age.seconds,
                        ttlSeconds = ttl.seconds,
                        observedAt = existing.storedAt,
                    )
                } catch (t: Throwable) {
                    cache.remove(key, existing)
                    throw t
                }
            }
            cache.remove(key, existing)
        }

        var createdFresh = false
        val entry =
            cache.computeIfAbsent(key) {
                createdFresh = true
                Entry(
                    deferred =
                        ownedScope.async {
                            client.fetch(contractCode, parkId, startDate, endDate)
                        },
                    storedAt = now,
                )
            }

        return try {
            CachedReserveAmericaResult(
                data = entry.deferred.await(),
                hit = !createdFresh,
                ageSeconds = Duration.between(entry.storedAt, Instant.now(clock)).seconds.coerceAtLeast(0),
                ttlSeconds = ttl.seconds,
                observedAt = entry.storedAt,
            )
        } catch (t: Throwable) {
            cache.remove(key, entry)
            throw t
        }
    }

    fun clear() = cache.clear()

    fun putForTest(
        key: Key,
        data: ReserveAmericaAvailability,
        storedAt: Instant = Instant.now(clock),
    ) {
        cache[key] = Entry(CompletableDeferred(data), storedAt)
    }
}

data class CachedReserveAmericaResult(
    val data: ReserveAmericaAvailability,
    val hit: Boolean,
    val ageSeconds: Long,
    val ttlSeconds: Long,
    val observedAt: Instant,
)
