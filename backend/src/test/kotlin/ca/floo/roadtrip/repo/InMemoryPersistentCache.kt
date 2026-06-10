package ca.floo.roadtrip.repo

import kotlinx.serialization.json.JsonElement
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemoryPersistentCache(
    private val clock: Clock = Clock.systemUTC(),
) : PersistentCache {
    private val entries = ConcurrentHashMap<Pair<String, String>, PersistentCacheEntry>()

    override fun get(
        namespace: String,
        key: String,
    ): PersistentCacheEntry? {
        val cacheKey = namespace to key
        val entry = entries[cacheKey] ?: return null
        if (!entry.expiresAt.isAfter(Instant.now(clock))) {
            entries.remove(cacheKey)
            return null
        }
        return entry
    }

    override fun put(
        namespace: String,
        key: String,
        payload: JsonElement,
        ttl: Duration,
    ) {
        val now = Instant.now(clock)
        entries[namespace to key] =
            PersistentCacheEntry(
                payload = payload,
                createdAt = now,
                expiresAt = now.plus(ttl),
            )
    }

    override fun delete(
        namespace: String,
        key: String,
    ) {
        entries.remove(namespace to key)
    }
}
