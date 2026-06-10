package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.references.API_CACHE
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jooq.DSLContext
import org.jooq.JSONB
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

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

data class PersistentCacheEntry(
    val payload: JsonElement,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    fun ageSeconds(clock: Clock): Long = Duration.between(createdAt, Instant.now(clock)).seconds.coerceAtLeast(0)

    fun ttlSeconds(): Long = Duration.between(createdAt, expiresAt).seconds.coerceAtLeast(0)
}

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

class ApiCacheRepo(
    private val ctx: DSLContext,
    private val clock: Clock = Clock.systemUTC(),
    private val json: Json = Json,
) : PersistentCache {
    override fun get(
        namespace: String,
        key: String,
    ): PersistentCacheEntry? {
        require(namespace.isNotBlank()) { "namespace must not be blank" }
        require(key.isNotBlank()) { "key must not be blank" }

        val row =
            ctx
                .select(API_CACHE.PAYLOAD, API_CACHE.CREATED_AT, API_CACHE.EXPIRES_AT)
                .from(API_CACHE)
                .where(API_CACHE.NAMESPACE.eq(namespace).and(API_CACHE.CACHE_KEY.eq(key)))
                .fetchOne() ?: return null

        val expiresAt = row.get(API_CACHE.EXPIRES_AT)?.toInstant() ?: return null
        if (!expiresAt.isAfter(Instant.now(clock))) {
            delete(namespace, key)
            return null
        }

        val payload = row.get(API_CACHE.PAYLOAD) ?: return null
        val createdAt = row.get(API_CACHE.CREATED_AT)?.toInstant() ?: return null
        val parsedPayload =
            try {
                json.parseToJsonElement(payload.data())
            } catch (e: Exception) {
                delete(namespace, key)
                return null
            }
        return PersistentCacheEntry(
            payload = parsedPayload,
            createdAt = createdAt,
            expiresAt = expiresAt,
        )
    }

    override fun put(
        namespace: String,
        key: String,
        payload: JsonElement,
        ttl: Duration,
    ) {
        require(namespace.isNotBlank()) { "namespace must not be blank" }
        require(key.isNotBlank()) { "key must not be blank" }
        require(!ttl.isNegative && !ttl.isZero) { "ttl must be positive" }

        val now = OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC)
        val expiresAt = now.plus(ttl)
        val payloadJson = JSONB.valueOf(json.encodeToString(JsonElement.serializer(), payload))

        ctx
            .insertInto(API_CACHE)
            .set(API_CACHE.NAMESPACE, namespace)
            .set(API_CACHE.CACHE_KEY, key)
            .set(API_CACHE.PAYLOAD, payloadJson)
            .set(API_CACHE.CREATED_AT, now)
            .set(API_CACHE.EXPIRES_AT, expiresAt)
            .onConflict(API_CACHE.NAMESPACE, API_CACHE.CACHE_KEY)
            .doUpdate()
            .set(API_CACHE.PAYLOAD, payloadJson)
            .set(API_CACHE.CREATED_AT, now)
            .set(API_CACHE.EXPIRES_AT, expiresAt)
            .execute()
    }

    override fun delete(
        namespace: String,
        key: String,
    ) {
        ctx
            .deleteFrom(API_CACHE)
            .where(API_CACHE.NAMESPACE.eq(namespace).and(API_CACHE.CACHE_KEY.eq(key)))
            .execute()
    }

    fun pruneExpired(): Int =
        ctx
            .deleteFrom(API_CACHE)
            .where(API_CACHE.EXPIRES_AT.le(OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC)))
            .execute()
}
