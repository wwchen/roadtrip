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
        assertEquals(Duration.ofHours(2), config.cache.ttlFor(ApiCacheEntity.CAMPFLARE_AVAILABILITY))
        assertEquals(Duration.ofHours(2), config.cache.ttlFor(ApiCacheEntity.ASPIRA_AVAILABILITY))
        assertEquals(Duration.ofHours(2), config.cache.ttlFor(ApiCacheEntity.RESERVEAMERICA_AVAILABILITY))
        assertEquals(Duration.ofHours(2), config.cache.ttlFor(ApiCacheEntity.RESERVECALIFORNIA_AVAILABILITY))
    }

    @Test
    fun `cache config parses iso and shorthand durations`() {
        val config =
            AppConfig.fromEnv(
                mapOf(
                    "ROADTRIP_CACHE_ROUTE_TTL" to "PT30M",
                    "ROADTRIP_CACHE_RECGOV_AVAILABILITY_TTL" to "4h",
                    "ROADTRIP_CACHE_CAMPFLARE_AVAILABILITY_TTL" to "20m",
                    "ROADTRIP_CACHE_ASPIRA_AVAILABILITY_TTL" to "900",
                    "ROADTRIP_CACHE_RESERVEAMERICA_AVAILABILITY_TTL" to "30m",
                    "ROADTRIP_CACHE_RESERVECALIFORNIA_AVAILABILITY_TTL" to "45m",
                ),
            )

        assertEquals(Duration.ofMinutes(30), config.cache.ttlFor(ApiCacheEntity.ROUTE))
        assertEquals(Duration.ofHours(4), config.cache.ttlFor(ApiCacheEntity.RECGOV_AVAILABILITY))
        assertEquals(Duration.ofMinutes(20), config.cache.ttlFor(ApiCacheEntity.CAMPFLARE_AVAILABILITY))
        assertEquals(Duration.ofMinutes(15), config.cache.ttlFor(ApiCacheEntity.ASPIRA_AVAILABILITY))
        assertEquals(Duration.ofMinutes(30), config.cache.ttlFor(ApiCacheEntity.RESERVEAMERICA_AVAILABILITY))
        assertEquals(Duration.ofMinutes(45), config.cache.ttlFor(ApiCacheEntity.RESERVECALIFORNIA_AVAILABILITY))
    }

    @Test
    fun `campflare config trims api key and base url with default fallback`() {
        assertEquals(null, AppConfig.fromEnv(emptyMap()).campflare.apiKey)
        assertEquals("https://api.campflare.com/v2", AppConfig.fromEnv(emptyMap()).campflare.apiBaseUrl)
        assertEquals(CampflareAvailabilityMode.AUTO, AppConfig.fromEnv(emptyMap()).campflare.availabilityMode)
        assertEquals(false, AppConfig.fromEnv(emptyMap()).campflare.usesCampflareAvailability)

        val config =
            AppConfig.fromEnv(
                mapOf(
                    "CAMPFLARE_API_KEY" to " key-123 ",
                    "CAMPFLARE_API_BASE" to " https://campflare.test/v2 ",
                ),
            )

        assertEquals("key-123", config.campflare.apiKey)
        assertEquals("https://campflare.test/v2", config.campflare.apiBaseUrl)
        assertEquals(CampflareAvailabilityMode.AUTO, config.campflare.availabilityMode)
        assertEquals(true, config.campflare.usesCampflareAvailability)
    }

    @Test
    fun `campflare availability mode toggles live provider use`() {
        assertEquals(
            false,
            AppConfig
                .fromEnv(mapOf("CAMPFLARE_API_KEY" to "key-123", "CAMPFLARE_AVAILABILITY_MODE" to "recgov"))
                .campflare
                .usesCampflareAvailability,
        )
        assertEquals(
            true,
            AppConfig
                .fromEnv(mapOf("CAMPFLARE_AVAILABILITY_MODE" to "campflare"))
                .campflare
                .usesCampflareAvailability,
        )
        assertEquals(
            false,
            AppConfig
                .fromEnv(mapOf("CAMPFLARE_AVAILABILITY_MODE" to "auto"))
                .campflare
                .usesCampflareAvailability,
        )
    }

    @Test
    fun `campflare availability mode rejects unknown values`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                AppConfig.fromEnv(mapOf("CAMPFLARE_AVAILABILITY_MODE" to "sometimes"))
            }

        assertEquals("CAMPFLARE_AVAILABILITY_MODE must be one of auto, campflare, recgov", err.message)
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

    @Test
    fun `slack config is null when token or channel is absent or blank`() {
        assertEquals(null, AppConfig.fromEnv(emptyMap()).slack)
        assertEquals(null, AppConfig.fromEnv(mapOf("SLACK_BOT_TOKEN" to "xoxb-x")).slack)
        assertEquals(null, AppConfig.fromEnv(mapOf("SLACK_ALERT_CHANNEL" to "#c")).slack)
        assertEquals(
            null,
            AppConfig.fromEnv(mapOf("SLACK_BOT_TOKEN" to "  ", "SLACK_ALERT_CHANNEL" to "#c")).slack,
        )
    }

    @Test
    fun `slack config is populated and trimmed when both token and channel are set`() {
        val slack =
            AppConfig
                .fromEnv(mapOf("SLACK_BOT_TOKEN" to " xoxb-abc ", "SLACK_ALERT_CHANNEL" to " #camping "))
                .slack

        assertEquals("xoxb-abc", slack?.botToken)
        assertEquals("#camping", slack?.defaultChannel)
        assertEquals(null, slack?.signingSecret)
    }

    @Test
    fun `slack signing secret is trimmed and populated when set, null when absent or blank`() {
        val enabled =
            AppConfig
                .fromEnv(
                    mapOf(
                        "SLACK_BOT_TOKEN" to "xoxb-a",
                        "SLACK_ALERT_CHANNEL" to "#c",
                        "SLACK_SIGNING_SECRET" to "  s3cr3t  ",
                    ),
                ).slack
        assertEquals("s3cr3t", enabled?.signingSecret)

        val blank =
            AppConfig
                .fromEnv(mapOf("SLACK_BOT_TOKEN" to "xoxb-a", "SLACK_ALERT_CHANNEL" to "#c", "SLACK_SIGNING_SECRET" to "   "))
                .slack
        assertEquals(null, blank?.signingSecret)
    }

    @Test
    fun `grafana config is null when the host is unset (no hardcoded default)`() {
        assertEquals(null, AppConfig.fromEnv(emptyMap()).grafana)
        assertEquals(null, AppConfig.fromEnv(mapOf("GRAFANA_ROOT_URL" to "  ")).grafana)
    }

    @Test
    fun `grafana root url is taken from env with any trailing slash stripped`() {
        assertEquals(
            "http://localhost:3000/dash",
            AppConfig.fromEnv(mapOf("GRAFANA_ROOT_URL" to "http://localhost:3000/dash/")).grafana?.rootUrl,
        )
    }

    @Test
    fun `web app config is null when the host is unset (no hardcoded default)`() {
        assertEquals(null, AppConfig.fromEnv(emptyMap()).webApp)
        assertEquals(null, AppConfig.fromEnv(mapOf("APP_ROOT_URL" to "  ")).webApp)
    }

    @Test
    fun `web app root url is taken from env with any trailing slash stripped`() {
        assertEquals(
            "https://roadtrip.floo.ca",
            AppConfig.fromEnv(mapOf("APP_ROOT_URL" to "https://roadtrip.floo.ca/")).webApp?.rootUrl,
        )
    }
}
