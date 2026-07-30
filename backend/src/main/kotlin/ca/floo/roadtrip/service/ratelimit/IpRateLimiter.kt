package ca.floo.roadtrip.service.ratelimit

import java.util.concurrent.ConcurrentHashMap

private const val MILLIS_PER_MINUTE = 60_000.0

/**
 * In-memory per-key token bucket, refilled continuously at [perMinute] tokens
 * per minute up to a burst capacity of [perMinute]. Keys are typically caller
 * IPs; each key gets its own bucket.
 *
 * Unlike [VendorRateLimiter] (durable, Postgres-backed, guards *outbound*
 * vendor budgets), this limiter is process-local and guards *inbound* abuse on
 * anonymous endpoints — state intentionally resets on restart.
 *
 * The limit and clock are constructor-injected so tests can pin both.
 */
class IpRateLimiter(
    private val perMinute: Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private class Bucket(
        var tokens: Double,
        var lastRefillMs: Long,
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val refillPerMs = perMinute / MILLIS_PER_MINUTE

    fun allow(key: String): Boolean {
        val now = nowMs()
        val bucket =
            buckets.compute(key) { _, existing ->
                val b = existing ?: Bucket(perMinute.toDouble(), now)
                val delta = now - b.lastRefillMs
                b.tokens = (b.tokens + delta * refillPerMs).coerceAtMost(perMinute.toDouble())
                b.lastRefillMs = now
                b
            }!!
        return synchronized(bucket) {
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                true
            } else {
                false
            }
        }
    }
}
