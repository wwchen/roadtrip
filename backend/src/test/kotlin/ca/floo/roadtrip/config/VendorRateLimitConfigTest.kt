package ca.floo.roadtrip.config

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
    fun `fromConfig parses a per-vendor override with a simple duration`() {
        val config =
            VendorRateLimitConfig.fromConfig(
                ConfigSection(
                    mapOf(
                        "roadtrip.vendor-rate-limit.aspira.capacity" to "5",
                        "roadtrip.vendor-rate-limit.aspira.refill-tokens" to "5",
                        "roadtrip.vendor-rate-limit.aspira.refill-period" to "10s",
                    ),
                ).section("roadtrip.vendor-rate-limit"),
            )
        val aspira = config.forVendor("aspira")
        assertEquals(5, aspira.capacity)
        assertEquals(5, aspira.refillTokens)
        assertEquals(Duration.ofSeconds(10), aspira.refillPeriod)
        // Untouched vendors still fall through to the default.
        assertEquals(DEFAULT_VENDOR_BUCKET_CAPACITY, config.forVendor("recgov").capacity)
    }

    @Test
    fun `fromConfig fills missing sub-keys from defaults when only capacity is set`() {
        val config =
            VendorRateLimitConfig.fromConfig(
                ConfigSection(
                    mapOf("roadtrip.vendor-rate-limit.recgov.capacity" to "100"),
                ).section("roadtrip.vendor-rate-limit"),
            )
        val recgov = config.forVendor("recgov")
        assertEquals(100, recgov.capacity)
        assertEquals(DEFAULT_VENDOR_BUCKET_REFILL_TOKENS, recgov.refillTokens)
        assertEquals(DEFAULT_VENDOR_BUCKET_REFILL_PERIOD, recgov.refillPeriod)
    }

    @Test
    fun `fromConfig with no vendor keys yields all-default config`() {
        val config = VendorRateLimitConfig.fromConfig(ConfigSection(mapOf("some.other.key" to "x")).section("roadtrip.vendor-rate-limit"))
        assertEquals(DEFAULT_VENDOR_BUCKET_CAPACITY, config.forVendor("recgov").capacity)
    }
}
