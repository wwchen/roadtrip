package ca.floo.roadtrip.config

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppConfigTest {
    @Test
    fun `cache config uses entity defaults when properties are empty`() {
        val config = AppConfig.fromProperties(emptyMap())

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
            AppConfig.fromProperties(
                mapOf(
                    "roadtrip.cache.route.ttl" to "PT30M",
                    "roadtrip.cache.recgov-availability.ttl" to "4h",
                    "roadtrip.cache.campflare-availability.ttl" to "20m",
                    "roadtrip.cache.aspira-availability.ttl" to "900",
                    "roadtrip.cache.reserveamerica-availability.ttl" to "30m",
                    "roadtrip.cache.reservecalifornia-availability.ttl" to "45m",
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
        assertEquals(null, AppConfig.fromProperties(emptyMap()).campflare.apiKey)
        assertEquals("https://api.campflare.com/v2", AppConfig.fromProperties(emptyMap()).campflare.apiBaseUrl)

        val config =
            AppConfig.fromProperties(
                mapOf(
                    "roadtrip.campflare.api-key" to " key-123 ",
                    "roadtrip.campflare.api-base-url" to " https://campflare.test/v2 ",
                ),
            )

        assertEquals("key-123", config.campflare.apiKey)
        assertEquals("https://campflare.test/v2", config.campflare.apiBaseUrl)
    }

    @Test
    fun `read path provider config parses comma separated allow lists`() {
        val config =
            AppConfig.fromProperties(
                mapOf(
                    "roadtrip.read-path.enabled-data-sources" to " recgov, campflare ,tesla_supercharger ",
                    "roadtrip.read-path.enabled-availability-providers" to " RECGOV, campflare ",
                ),
            )

        assertEquals(
            setOf("recgov", "campflare", "tesla_supercharger"),
            config.readPathProviders.enabledDataSources,
        )
        assertEquals(setOf("recgov", "campflare"), config.readPathProviders.enabledAvailabilityProviders)
    }

    @Test
    fun `read path provider config rejects unknown availability provider ids`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                AppConfig.fromProperties(mapOf("roadtrip.read-path.enabled-availability-providers" to "recgov,wat"))
            }

        assertEquals(
            "roadtrip.read-path.enabled-availability-providers contains unknown provider(s): " +
                "[wat]. Expected one of: [aspira, campflare, recgov, reserveamerica, reservecalifornia].",
            err.message,
        )
    }

    @Test
    fun `blank read path provider config disables data sources and availability providers`() {
        val config =
            AppConfig
                .fromProperties(
                    mapOf(
                        "roadtrip.read-path.enabled-data-sources" to "",
                        "roadtrip.read-path.enabled-availability-providers" to "",
                    ),
                ).readPathProviders

        assertEquals(emptySet(), config.enabledDataSources)
        assertEquals(emptySet(), config.enabledAvailabilityProviders)
        assertEquals(false, config.isDataSourceEnabled("recgov"))
        assertEquals(false, config.isAvailabilityProviderEnabled("recgov"))
    }

    @Test
    fun `db config uses local defaults when properties are empty`() {
        val config = DbConfig.fromProperties(emptyMap())

        assertEquals("jdbc:postgresql://localhost:5432/roadtrip", config.jdbcUrl)
        assertEquals("roadtrip", config.user)
        assertEquals("roadtrip", config.password)
    }

    @Test
    fun `db config reads property overrides`() {
        val config =
            DbConfig.fromProperties(
                mapOf(
                    "roadtrip.db.url" to "jdbc:postgresql://db.internal:5432/roadtrip",
                    "roadtrip.db.user" to "app",
                    "roadtrip.db.password" to "secret",
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
                AppConfig.fromProperties(mapOf("roadtrip.cache.route.ttl" to "forever"))
            }

        assertEquals(
            "roadtrip.cache.route.ttl must be an ISO-8601 duration or a number with ms/s/m/h/d",
            err.message,
        )
    }

    @Test
    fun `cache config rejects non-positive durations`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                AppConfig.fromProperties(mapOf("roadtrip.cache.route.ttl" to "0s"))
            }

        assertEquals("roadtrip.cache.route.ttl must be positive", err.message)
    }

    @Test
    fun `slack config is null when token or channel is absent or blank`() {
        assertEquals(null, AppConfig.fromProperties(emptyMap()).slack)
        assertEquals(null, AppConfig.fromProperties(mapOf("roadtrip.slack.bot-token" to "xoxb-x")).slack)
        assertEquals(null, AppConfig.fromProperties(mapOf("roadtrip.slack.default-channel" to "#c")).slack)
        assertEquals(
            null,
            AppConfig.fromProperties(mapOf("roadtrip.slack.bot-token" to "  ", "roadtrip.slack.default-channel" to "#c")).slack,
        )
    }

    @Test
    fun `slack config is populated and trimmed when both token and channel are set`() {
        val slack =
            AppConfig
                .fromProperties(
                    mapOf(
                        "roadtrip.slack.bot-token" to " xoxb-abc ",
                        "roadtrip.slack.default-channel" to " #camping ",
                    ),
                ).slack

        assertEquals("xoxb-abc", slack?.botToken)
        assertEquals("#camping", slack?.defaultChannel)
        assertEquals(null, slack?.signingSecret)
    }

    @Test
    fun `slack signing secret is trimmed and populated when set, null when absent or blank`() {
        val enabled =
            AppConfig
                .fromProperties(
                    mapOf(
                        "roadtrip.slack.bot-token" to "xoxb-a",
                        "roadtrip.slack.default-channel" to "#c",
                        "roadtrip.slack.signing-secret" to "  s3cr3t  ",
                    ),
                ).slack
        assertEquals("s3cr3t", enabled?.signingSecret)

        val blank =
            AppConfig
                .fromProperties(
                    mapOf(
                        "roadtrip.slack.bot-token" to "xoxb-a",
                        "roadtrip.slack.default-channel" to "#c",
                        "roadtrip.slack.signing-secret" to "   ",
                    ),
                ).slack
        assertEquals(null, blank?.signingSecret)
    }

    @Test
    fun `grafana config is null when the host is unset (no hardcoded default)`() {
        assertEquals(null, AppConfig.fromProperties(emptyMap()).grafana)
        assertEquals(null, AppConfig.fromProperties(mapOf("roadtrip.grafana.root-url" to "  ")).grafana)
    }

    @Test
    fun `grafana root url is taken from properties with any trailing slash stripped`() {
        assertEquals(
            "http://localhost:3000/dash",
            AppConfig.fromProperties(mapOf("roadtrip.grafana.root-url" to "http://localhost:3000/dash/")).grafana?.rootUrl,
        )
    }

    @Test
    fun `web app config is null when the host is unset (no hardcoded default)`() {
        assertEquals(null, AppConfig.fromProperties(emptyMap()).webApp)
        assertEquals(null, AppConfig.fromProperties(mapOf("roadtrip.web.root-url" to "  ")).webApp)
    }

    @Test
    fun `web app root url is taken from properties with any trailing slash stripped`() {
        assertEquals(
            "https://roadtrip.floo.ca",
            AppConfig.fromProperties(mapOf("roadtrip.web.root-url" to "https://roadtrip.floo.ca/")).webApp?.rootUrl,
        )
    }
}
