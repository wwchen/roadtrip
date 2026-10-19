package ca.floo.roadtrip.config

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StayCheckConfigTest {
    @Test
    fun `defaults are used when the section is absent`() {
        val config = StayCheckConfig.default
        assertEquals(50, config.maxPois)
        assertEquals(3, config.fanOutConcurrency)
        assertEquals(Duration.ofHours(2), config.defaultMaxAge)
        assertEquals(150, config.ipPoiLimitPerMinute)
        assertEquals(Duration.ofMinutes(1), config.minMaxAge)
        assertEquals(Duration.ofSeconds(45), config.perPoiTimeout)
        assertEquals(Duration.ofMinutes(2), config.streamTimeout)
        assertEquals(Duration.ofSeconds(15), config.streamHeartbeat)
    }

    @Test
    fun `a stream's heartbeat may not be slower than a client waits for a sign of life`() {
        val atCeiling = Duration.ofSeconds(StayCheckConfig.MAX_STREAM_HEARTBEAT_SECONDS)
        assertEquals(atCeiling, StayCheckConfig.default.copy(streamHeartbeat = atCeiling).streamHeartbeat)
        assertFailsWith<IllegalArgumentException> {
            StayCheckConfig.default.copy(streamHeartbeat = atCeiling.plusSeconds(1))
        }
    }

    @Test
    fun `the per-caller budget covers at least one request of the largest size`() {
        assertFailsWith<IllegalArgumentException> {
            StayCheckConfig(maxPois = 50, fanOutConcurrency = 3, defaultMaxAge = Duration.ofHours(2), ipPoiLimitPerMinute = 49)
        }
    }

    @Test
    fun `min-max-age above default-max-age is refused`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                StayCheckConfig(
                    maxPois = 50,
                    fanOutConcurrency = 3,
                    defaultMaxAge = Duration.ofMinutes(5),
                    ipPoiLimitPerMinute = 150,
                    minMaxAge = Duration.ofMinutes(10),
                )
            }
        assertTrue(err.message!!.contains("min-max-age"), err.message)
        assertTrue(err.message!!.contains("default-max-age"), err.message)
    }

    @Test
    fun `the config reads the renamed keys`() {
        val section =
            ConfigSection(
                mapOf(
                    "default-max-age" to "30m",
                    "min-max-age" to "10s",
                ),
            )
        val config = StayCheckConfig.fromConfig(section)
        assertEquals(Duration.ofMinutes(30), config.defaultMaxAge)
        assertEquals(Duration.ofSeconds(10), config.minMaxAge)
    }

    @Test
    fun `max pois must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            StayCheckConfig(
                maxPois = 0,
                fanOutConcurrency = 8,
                defaultMaxAge = Duration.ofHours(2),
                ipPoiLimitPerMinute = 150,
            )
        }
    }

    @Test
    fun `fan out concurrency must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            StayCheckConfig(
                maxPois = 50,
                fanOutConcurrency = 0,
                defaultMaxAge = Duration.ofHours(2),
                ipPoiLimitPerMinute = 150,
            )
        }
    }
}
