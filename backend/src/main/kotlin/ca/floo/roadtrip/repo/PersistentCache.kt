package ca.floo.roadtrip.repo

import kotlinx.serialization.json.JsonElement
import java.time.Duration

interface PersistentCache {
    fun get(
        namespace: String,
        key: String,
    ): PersistentCacheEntry?

    fun put(
        namespace: String,
        key: String,
        payload: JsonElement,
        ttl: Duration,
    )

    fun delete(
        namespace: String,
        key: String,
    )
}
