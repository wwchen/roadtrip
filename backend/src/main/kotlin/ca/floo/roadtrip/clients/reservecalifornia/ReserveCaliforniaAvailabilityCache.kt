package ca.floo.roadtrip.clients.reservecalifornia

import ca.floo.roadtrip.config.ApiCacheEntity
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

class CachedReserveCaliforniaAvailability(
    private val client: ReserveCaliforniaAvailabilityClient,
    private val ttl: Duration = ApiCacheEntity.RESERVECALIFORNIA_AVAILABILITY.defaultTtl,
    private val clock: Clock = Clock.systemUTC(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val ownedScope = scope

    data class Key(
        val facilityId: Long,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val minDate: LocalDate,
        val maxDate: LocalDate,
    )

    private data class Entry(
        val deferred: Deferred<ReserveCaliforniaGridAvailability>,
        val storedAt: Instant,
    )

    private val cache = ConcurrentHashMap<Key, Entry>()

    suspend fun getGrid(
        facilityId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
        minDate: LocalDate,
        maxDate: LocalDate,
        force: Boolean = false,
    ): CachedReserveCaliforniaGridResult {
        val key = Key(facilityId, startDate, endDate, minDate, maxDate)
        val now = Instant.now(clock)
        if (force) cache.remove(key)

        val existing = cache[key]
        if (existing != null) {
            val age = Duration.between(existing.storedAt, now)
            if (age < ttl) {
                return try {
                    CachedReserveCaliforniaGridResult(
                        data = existing.deferred.await(),
                        hit = true,
                        ageSeconds = age.seconds,
                        ttlSeconds = ttl.seconds,
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
                            client.fetchGrid(facilityId, startDate, endDate, minDate, maxDate)
                        },
                    storedAt = now,
                )
            }
        return try {
            CachedReserveCaliforniaGridResult(
                data = entry.deferred.await(),
                hit = !createdFresh,
                ageSeconds = Duration.between(entry.storedAt, Instant.now(clock)).seconds.coerceAtLeast(0),
                ttlSeconds = ttl.seconds,
            )
        } catch (t: Throwable) {
            cache.remove(key, entry)
            throw t
        }
    }

    fun clear() = cache.clear()
}

data class CachedReserveCaliforniaGridResult(
    val data: ReserveCaliforniaGridAvailability,
    val hit: Boolean,
    val ageSeconds: Long,
    val ttlSeconds: Long,
)
