package ca.floo.roadtrip.config

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BulkAvailabilityConfigTest {
    @Test
    fun `defaults are used when the section is absent`() {
        val config = BulkAvailabilityConfig.default
        assertEquals(50, config.maxPois)
        assertEquals(3, config.fanOutConcurrency)
        assertEquals(Duration.ofSeconds(20), config.perPoiTimeout)
        assertEquals(Duration.ofHours(2), config.tolerance)
        assertEquals(10, config.ipRateLimitPerMinute)
    }

    @Test
    fun `max pois must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            BulkAvailabilityConfig(
                maxPois = 0,
                fanOutConcurrency = 8,
                perPoiTimeout = Duration.ofSeconds(20),
                tolerance = Duration.ofHours(2),
                ipRateLimitPerMinute = 10,
            )
        }
    }

    @Test
    fun `fan out concurrency must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            BulkAvailabilityConfig(
                maxPois = 50,
                fanOutConcurrency = 0,
                perPoiTimeout = Duration.ofSeconds(20),
                tolerance = Duration.ofHours(2),
                ipRateLimitPerMinute = 10,
            )
        }
    }

    @Test
    fun `per poi timeout must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            BulkAvailabilityConfig(
                maxPois = 50,
                fanOutConcurrency = 8,
                perPoiTimeout = Duration.ZERO,
                tolerance = Duration.ofHours(2),
                ipRateLimitPerMinute = 10,
            )
        }
    }
}
