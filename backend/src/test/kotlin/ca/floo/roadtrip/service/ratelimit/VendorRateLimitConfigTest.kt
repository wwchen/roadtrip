package ca.floo.roadtrip.service.ratelimit

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class VendorRateLimitConfigTest {
    @Test
    fun `unconfigured vendor gets the code-level default bucket`() {
        val config = VendorRateLimitConfig(overrides = emptyMap())
        val bucket = config.forVendor("recgov")
        assertEquals(DEFAULT_VENDOR_BUCKET_CAPACITY, bucket.capacity)
        assertEquals(DEFAULT_VENDOR_BUCKET_REFILL_TOKENS, bucket.refillTokens)
        assertEquals(DEFAULT_VENDOR_BUCKET_REFILL_PERIOD, bucket.refillPeriod)
    }

    @Test
    fun `configured vendor overrides the default while others stay untouched`() {
        val config =
            VendorRateLimitConfig(
                overrides =
                    mapOf(
                        "aspira" to VendorBucketConfig(capacity = 5, refillTokens = 5, refillPeriod = Duration.ofSeconds(10)),
                    ),
            )
        assertEquals(5, config.forVendor("aspira").capacity)
        assertEquals(Duration.ofSeconds(10), config.forVendor("aspira").refillPeriod)
        assertEquals(DEFAULT_VENDOR_BUCKET_CAPACITY, config.forVendor("recgov").capacity)
    }

    @Test
    fun `vendor lookup is case-insensitive`() {
        val config =
            VendorRateLimitConfig(
                overrides = mapOf("Aspira" to VendorBucketConfig(7, 7, Duration.ofSeconds(30))),
            )
        assertEquals(7, config.forVendor("ASPIRA").capacity)
        assertEquals(7, config.forVendor("aspira").capacity)
    }

    @Test
    fun `fromEnv parses a per-vendor override with a simple duration`() {
        val config =
            VendorRateLimitConfig.fromEnv(
                env =
                    mapOf(
                        "ROADTRIP_VENDOR_RATELIMIT_ASPIRA_CAPACITY" to "5",
                        "ROADTRIP_VENDOR_RATELIMIT_ASPIRA_REFILL_TOKENS" to "5",
                        "ROADTRIP_VENDOR_RATELIMIT_ASPIRA_REFILL_PERIOD" to "10s",
                    ),
            )
        val aspira = config.forVendor("aspira")
        assertEquals(5, aspira.capacity)
        assertEquals(5, aspira.refillTokens)
        assertEquals(Duration.ofSeconds(10), aspira.refillPeriod)
        // Untouched vendors still fall through to the default.
        assertEquals(DEFAULT_VENDOR_BUCKET_CAPACITY, config.forVendor("recgov").capacity)
    }

    @Test
    fun `fromEnv fills missing sub-keys from defaults when only capacity is set`() {
        val config =
            VendorRateLimitConfig.fromEnv(
                env = mapOf("ROADTRIP_VENDOR_RATELIMIT_RECGOV_CAPACITY" to "100"),
            )
        val recgov = config.forVendor("recgov")
        assertEquals(100, recgov.capacity)
        assertEquals(DEFAULT_VENDOR_BUCKET_REFILL_TOKENS, recgov.refillTokens)
        assertEquals(DEFAULT_VENDOR_BUCKET_REFILL_PERIOD, recgov.refillPeriod)
    }

    @Test
    fun `fromEnv with no vendor keys yields all-default config`() {
        val config = VendorRateLimitConfig.fromEnv(env = mapOf("SOME_OTHER_VAR" to "x"))
        assertEquals(DEFAULT_VENDOR_BUCKET_CAPACITY, config.forVendor("recgov").capacity)
    }
}
