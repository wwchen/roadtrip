package ca.floo.roadtrip.service.ratelimit

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ONE_SECOND_MS = 1_000L
private const val TEN_MINUTES_MS = 600_000L

class IpRateLimiterTest {
    @Test
    fun `allows a full burst up to the per-minute budget then denies`() {
        val limiter = IpRateLimiter(perMinute = 3, nowMs = { 0L })

        repeat(3) { assertTrue(limiter.allow("1.2.3.4"), "call ${it + 1} should be allowed") }
        assertFalse(limiter.allow("1.2.3.4"), "budget exhausted, call must be denied")
    }

    @Test
    fun `refills tokens continuously as time passes`() {
        var now = 0L
        // 60/min = one token per second.
        val limiter = IpRateLimiter(perMinute = 60, nowMs = { now })

        repeat(60) { assertTrue(limiter.allow("ip")) }
        assertFalse(limiter.allow("ip"))

        now += ONE_SECOND_MS
        assertTrue(limiter.allow("ip"), "one second refills exactly one token")
        assertFalse(limiter.allow("ip"), "the refilled token is spent")
    }

    @Test
    fun `refill is capped at the bucket capacity`() {
        var now = 0L
        val limiter = IpRateLimiter(perMinute = 2, nowMs = { now })
        assertTrue(limiter.allow("ip"))

        now += TEN_MINUTES_MS
        repeat(2) { assertTrue(limiter.allow("ip"), "long idle refills to capacity, not beyond") }
        assertFalse(limiter.allow("ip"))
    }

    @Test
    fun `each key gets its own bucket`() {
        val limiter = IpRateLimiter(perMinute = 1, nowMs = { 0L })

        assertTrue(limiter.allow("10.0.0.1"))
        assertFalse(limiter.allow("10.0.0.1"))
        assertTrue(limiter.allow("10.0.0.2"), "a different key must not share the exhausted bucket")
    }
}
