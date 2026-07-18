package ca.floo.roadtrip.model.domain.cache

import kotlinx.serialization.json.JsonElement
import java.time.Clock
import java.time.Duration
import java.time.Instant

class PersistentCacheEntry(
    val payload: JsonElement,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    fun ageSeconds(clock: Clock): Long = Duration.between(createdAt, Instant.now(clock)).seconds.coerceAtLeast(0)

    fun ttlSeconds(): Long = Duration.between(createdAt, expiresAt).seconds.coerceAtLeast(0)
}
