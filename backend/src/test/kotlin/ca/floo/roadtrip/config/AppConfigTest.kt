package ca.floo.roadtrip.config

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppConfigTest {
    @Test
    fun `cache config uses entity defaults when env is empty`() {
        val config = AppConfig.fromEnv(emptyMap())

        assertEquals(Duration.ofMinutes(10), config.cache.ttlFor(ApiCacheEntity.ROUTE))
        assertEquals(Duration.ofHours(2), config.cache.ttlFor(ApiCacheEntity.RECGOV_AVAILABILITY))
        assertEquals(Duration.ofHours(2), config.cache.ttlFor(ApiCacheEntity.ASPIRA_AVAILABILITY))
    }

    @Test
    fun `cache config parses iso and shorthand durations`() {
        val config =
            AppConfig.fromEnv(
                mapOf(
                    "ROADTRIP_CACHE_ROUTE_TTL" to "PT30M",
                    "ROADTRIP_CACHE_RECGOV_AVAILABILITY_TTL" to "4h",
                    "ROADTRIP_CACHE_ASPIRA_AVAILABILITY_TTL" to "900",
                ),
            )

        assertEquals(Duration.ofMinutes(30), config.cache.ttlFor(ApiCacheEntity.ROUTE))
        assertEquals(Duration.ofHours(4), config.cache.ttlFor(ApiCacheEntity.RECGOV_AVAILABILITY))
        assertEquals(Duration.ofMinutes(15), config.cache.ttlFor(ApiCacheEntity.ASPIRA_AVAILABILITY))
    }

    @Test
    fun `cache config rejects invalid durations`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                AppConfig.fromEnv(mapOf("ROADTRIP_CACHE_ROUTE_TTL" to "forever"))
            }

        assertEquals(
            "ROADTRIP_CACHE_ROUTE_TTL must be an ISO-8601 duration or a number with ms/s/m/h/d",
            err.message,
        )
    }

    @Test
    fun `cache config rejects non-positive durations`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                AppConfig.fromEnv(mapOf("ROADTRIP_CACHE_ROUTE_TTL" to "0s"))
            }

        assertEquals("ROADTRIP_CACHE_ROUTE_TTL must be positive", err.message)
    }
}
