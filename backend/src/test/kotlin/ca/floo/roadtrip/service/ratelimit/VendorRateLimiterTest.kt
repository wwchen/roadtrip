package ca.floo.roadtrip.service.ratelimit

import ca.floo.roadtrip.config.VendorBucketConfig
import ca.floo.roadtrip.config.VendorRateLimitConfig
import ca.floo.roadtrip.repo.SharedDbTest
import org.junit.jupiter.api.BeforeEach
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VendorRateLimiterTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM vendor_rate_limit_bucket")
    }

    @Test
    fun `tryAcquire succeeds up to capacity then fails until refill`() {
        val config =
            VendorRateLimitConfig(
                overrides = mapOf("recgov" to VendorBucketConfig(capacity = 2, refillTokens = 2, refillPeriod = Duration.ofSeconds(60))),
            )
        val limiter = VendorRateLimiter(config, ds)
        assertTrue(limiter.tryAcquire("recgov", 1))
        assertTrue(limiter.tryAcquire("recgov", 1))
        assertFalse(limiter.tryAcquire("recgov", 1)) // exhausted
    }

    @Test
    fun `acquiring more tokens than available fails without partial consumption`() {
        val config =
            VendorRateLimitConfig(
                overrides = mapOf("recgov" to VendorBucketConfig(capacity = 3, refillTokens = 3, refillPeriod = Duration.ofSeconds(60))),
            )
        val limiter = VendorRateLimiter(config, ds)
        assertFalse(limiter.tryAcquire("recgov", 5)) // > capacity, fails outright
        assertTrue(limiter.tryAcquire("recgov", 3)) // full bucket still intact
    }

    @Test
    fun `buckets for different vendors are independent`() {
        val config =
            VendorRateLimitConfig(
                overrides =
                    mapOf(
                        "recgov" to VendorBucketConfig(1, 1, Duration.ofSeconds(60)),
                        "aspira" to VendorBucketConfig(1, 1, Duration.ofSeconds(60)),
                    ),
            )
        val limiter = VendorRateLimiter(config, ds)
        assertTrue(limiter.tryAcquire("recgov", 1))
        assertTrue(limiter.tryAcquire("aspira", 1)) // separate bucket, not starved by recgov
    }

    @Test
    fun `budget survives limiter recreation (durable, not in-memory)`() {
        val config =
            VendorRateLimitConfig(
                overrides = mapOf("recgov" to VendorBucketConfig(1, 1, Duration.ofSeconds(60))),
            )
        assertTrue(VendorRateLimiter(config, ds).tryAcquire("recgov", 1))
        val secondInstance = VendorRateLimiter(config, ds) // simulates a restart
        assertFalse(secondInstance.tryAcquire("recgov", 1)) // still exhausted -- state was in Postgres
    }
}
