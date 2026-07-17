package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.cache.PersistentCacheEntry
import kotlinx.serialization.json.JsonElement
import java.time.Duration

object NoopPersistentCache : PersistentCache {
    override fun get(
        namespace: String,
        key: String,
    ): PersistentCacheEntry? = null

    override fun put(
        namespace: String,
        key: String,
        payload: JsonElement,
        ttl: Duration,
    ) {
        // Intentionally empty.
    }

    override fun delete(
        namespace: String,
        key: String,
    ) {
        // Intentionally empty.
    }
}
