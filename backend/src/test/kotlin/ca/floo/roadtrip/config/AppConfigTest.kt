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

        val config =
            AppConfig.fromEnv(
                mapOf(
                    "CAMPFLARE_API_KEY" to " key-123 ",
                    "CAMPFLARE_API_BASE" to " https://campflare.test/v2 ",
                ),
            )

        assertEquals("key-123", config.campflare.apiKey)
        assertEquals("https://campflare.test/v2", config.campflare.apiBaseUrl)
    }

    @Test
    fun `db config uses local defaults when env is empty`() {
        val config = DbConfig.fromEnv(emptyMap())

        assertEquals("jdbc:postgresql://localhost:5432/roadtrip", config.jdbcUrl)
        assertEquals("roadtrip", config.user)
        assertEquals("roadtrip", config.password)
    }

    @Test
    fun `db config reads env overrides`() {
        val config =
            DbConfig.fromEnv(
                mapOf(
                    "ROADTRIP_DB_URL" to "jdbc:postgresql://db.internal:5432/roadtrip",
                    "ROADTRIP_DB_USER" to "app",
                    "ROADTRIP_DB_PASSWORD" to "secret",
                ),
            )

        assertEquals("jdbc:postgresql://db.internal:5432/roadtrip", config.jdbcUrl)
        assertEquals("app", config.user)
        assertEquals("secret", config.password)
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
