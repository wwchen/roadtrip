package ca.floo.roadtrip.service.ratelimit

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val MILLIS_PER_MINUTE = 60_000.0

// Keys are unauthenticated caller IPs, so the map has to be bounded. A bucket
// left untouched long enough to refill completely is indistinguishable from a
// fresh one, which makes the sweep free of behaviour change; the hard cap is
// the backstop for a distinct-key flood arriving inside one sweep interval.
private const val IDLE_EVICT_MS = 5 * 60_000L
private const val SWEEP_INTERVAL_MS = 60_000L
private const val MAX_KEYS = 10_000

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
    private val lastSweepMs = AtomicLong(nowMs())

    fun allow(key: String): Boolean {
        val now = nowMs()
        evictStale(now)
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

    private fun evictStale(now: Long) {
        if (buckets.size > MAX_KEYS) {
            // Process-local best-effort state that already resets on restart;
            // dropping it beats growing without bound.
            buckets.clear()
            lastSweepMs.set(now)
            return
        }
        val last = lastSweepMs.get()
        if (now - last < SWEEP_INTERVAL_MS || !lastSweepMs.compareAndSet(last, now)) return
        buckets.entries.removeIf { entry ->
            synchronized(entry.value) { now - entry.value.lastRefillMs >= IDLE_EVICT_MS }
        }
    }
}
