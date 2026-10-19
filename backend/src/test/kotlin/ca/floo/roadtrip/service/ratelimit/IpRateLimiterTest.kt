package ca.floo.roadtrip.service.ratelimit

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ONE_SECOND_MS = 1_000L
private const val TEN_MINUTES_MS = 600_000L
private const val HALF_MINUTE_MS = 30_000L
private const val PAST_SWEEP_INTERVAL_MS = 61_000L
private const val STRESS_ROUNDS = 5
private const val STRESS_THREADS = 8
private const val STRESS_BUDGET = 5_000
private const val STRESS_CALLS_PER_THREAD = 1_000

class IpRateLimiterTest {
    @Test
    fun `allows a full burst up to the per-minute budget then denies`() {
        val limiter = IpRateLimiter(perMinute = 3, nowMs = { 0L })

        repeat(3) { assertTrue(limiter.allow("1.2.3.4"), "call ${it + 1} should be allowed") }
        assertFalse(limiter.allow("1.2.3.4"), "budget exhausted, call must be denied")
    }

    @Test
    fun `a costly call spends that many tokens, and is denied when fewer are left`() {
        val limiter = IpRateLimiter(perMinute = 5, nowMs = { 0L })

        assertTrue(limiter.allow("ip", cost = 3))
        assertFalse(limiter.allow("ip", cost = 3), "two left, three asked: denied, and nothing spent")
        assertTrue(limiter.allow("ip", cost = 2))
        assertFalse(limiter.allow("ip"))
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

    @Test
    fun `a sweep does not reset the bucket of a key that is still active`() {
        var now = 0L
        val limiter = IpRateLimiter(perMinute = 60, nowMs = { now })

        fun drain(): Int {
            var grants = 0
            while (limiter.allow("ip")) grants++
            return grants
        }

        assertEquals(60, drain(), "the first burst spends the whole budget")

        now = HALF_MINUTE_MS
        assertTrue(drain() in 29..31, "half a minute refills about half the budget")

        now = PAST_SWEEP_INTERVAL_MS
        val afterSweep = drain()
        assertTrue(afterSweep < 60, "a sweep must not hand an active key a fresh budget, got $afterSweep")
        assertTrue(afterSweep >= 29, "the surviving bucket still refills, got $afterSweep")
    }

    @Test
    fun `a key idle past the eviction horizon is served again at capacity`() {
        var now = 0L
        val limiter = IpRateLimiter(perMinute = 2, nowMs = { now })
        repeat(2) { assertTrue(limiter.allow("ip")) }
        assertFalse(limiter.allow("ip"))

        now = TEN_MINUTES_MS
        repeat(2) { assertTrue(limiter.allow("ip"), "an evicted key starts from a full bucket") }
        assertFalse(limiter.allow("ip"), "and from a bucket of the same capacity")
    }

    @Test
    fun `concurrent callers on one key never spend more than the budget`() {
        repeat(STRESS_ROUNDS) { round ->
            val limiter = IpRateLimiter(perMinute = STRESS_BUDGET, nowMs = { 0L })
            val granted = AtomicInteger()
            val start = CountDownLatch(1)
            val threads =
                (1..STRESS_THREADS).map {
                    Thread {
                        start.await()
                        repeat(STRESS_CALLS_PER_THREAD) {
                            if (limiter.allow("1.2.3.4")) granted.incrementAndGet()
                        }
                    }
                }
            threads.forEach(Thread::start)
            start.countDown()
            threads.forEach(Thread::join)

            assertEquals(
                STRESS_BUDGET,
                granted.get(),
                "round $round: a frozen clock refills nothing, so exactly the budget may be spent",
            )
        }
    }
}
