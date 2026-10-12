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
        assertEquals(Duration.ofHours(2), config.tolerance)
        assertEquals(10, config.ipRateLimitPerMinute)
        assertEquals(Duration.ofMinutes(1), config.freshTolerance)
    }

    @Test
    fun `fresh tolerance may not exceed the usual tolerance`() {
        assertFailsWith<IllegalArgumentException> {
            BulkAvailabilityConfig(
                maxPois = 50,
                fanOutConcurrency = 3,
                tolerance = Duration.ofMinutes(5),
                ipRateLimitPerMinute = 10,
                freshTolerance = Duration.ofMinutes(10),
            )
        }
    }

    @Test
    fun `max pois must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            BulkAvailabilityConfig(
                maxPois = 0,
                fanOutConcurrency = 8,
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
                tolerance = Duration.ofHours(2),
                ipRateLimitPerMinute = 10,
            )
        }
    }
}
