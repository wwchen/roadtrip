package ca.floo.roadtrip.config

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AppConfigTest {
    private val requiredAvailabilityProperties =
        mapOf(
            "roadtrip.availability.force-pull-cooldown" to "60s",
            "roadtrip.availability.provider-cooldown" to "5m",
        )

    private fun appConfig(properties: Map<String, String> = emptyMap()): AppConfig =
        AppConfig.fromProperties(requiredAvailabilityProperties + properties)

    @Test
    fun `availability config parses cooldown durations`() {
        val config =
            appConfig(
                mapOf(
                    "roadtrip.availability.force-pull-cooldown" to "42s",
                    "roadtrip.availability.provider-cooldown" to "7m",
                ),
            )

        assertEquals(Duration.ofSeconds(42), config.availability.forcePullCooldown)
        assertEquals(Duration.ofMinutes(7), config.availability.providerCooldown)
    }

    @Test
    fun `availability config requires cooldown durations`() {
        val missingForcePull =
            assertFailsWith<IllegalArgumentException> {
                AppConfig.fromProperties(mapOf("roadtrip.availability.provider-cooldown" to "5m"))
            }
        assertEquals("roadtrip.availability.force-pull-cooldown is required", missingForcePull.message)

        val missingProvider =
            assertFailsWith<IllegalArgumentException> {
                AppConfig.fromProperties(mapOf("roadtrip.availability.force-pull-cooldown" to "60s"))
            }
        assertEquals("roadtrip.availability.provider-cooldown is required", missingProvider.message)
    }

    @Test
    fun `booking config disables recgov companion by default`() {
        val config = appConfig().booking.recgovAtc

        assertNull(config.companionBaseUrl)
        assertEquals(false, config.companionEnabled)
        assertEquals(Duration.ofSeconds(180), config.companionTimeout)
    }

    @Test
    fun `booking config parses recgov companion settings`() {
        val config =
            appConfig(
                mapOf(
                    "roadtrip.booking.recgov-atc.companion-base-url" to " http://recgov-companion:8770/ ",
                    "roadtrip.booking.recgov-atc.companion-timeout" to "45s",
                ),
            ).booking.recgovAtc

        assertEquals("http://recgov-companion:8770", config.companionBaseUrl)
        assertEquals(true, config.companionEnabled)
        assertEquals(Duration.ofSeconds(45), config.companionTimeout)
    }

    @Test
    fun `email config allows explicit recipient sends without default recipients`() {
        val config =
            appConfig(
                mapOf(
                    "roadtrip.email.resend-api-key" to " re_test ",
                    "roadtrip.email.from" to " Roadtrip Alerts <roadtrip@floo.ca> ",
                ),
            ).email

        assertEquals("re_test", config?.resendApiKey)
        assertEquals("Roadtrip Alerts <roadtrip@floo.ca>", config?.from)
    }

    @Test
    fun `email config is disabled without resend api key or sender`() {
        assertNull(
            appConfig(
                mapOf(
                    "roadtrip.email.from" to "Roadtrip Alerts <roadtrip@floo.ca>",
                ),
            ).email,
        )
        assertNull(
            appConfig(
                mapOf(
                    "roadtrip.email.resend-api-key" to "re_test",
                ),
            ).email,
        )
    }

    @Test
    fun `cache config uses entity defaults when properties are empty`() {
        val config = appConfig()

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
            appConfig(
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
        assertEquals(null, appConfig().campflare.apiKey)
        assertEquals("https://api.campflare.com/v2", appConfig().campflare.apiBaseUrl)

        val config =
            appConfig(
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
            appConfig(
                mapOf(
                    "roadtrip.read-path.enabled-data-providers" to " recgov, campflare ,tesla_supercharger ",
                    "roadtrip.read-path.enabled-availability-providers" to " RECGOV, campflare ",
                ),
            )

        assertEquals(
            setOf("recgov", "campflare", "tesla_supercharger"),
            config.readPathProviders.enabledDataProviders,
        )
        assertEquals(setOf("recgov", "campflare"), config.readPathProviders.enabledAvailabilityProviders)
    }

    @Test
    fun `vendor rate limit config is exposed through app config`() {
        val config =
            appConfig(
                mapOf(
                    "roadtrip.vendor-rate-limit.aspira.capacity" to "5",
                    "roadtrip.vendor-rate-limit.aspira.refill-tokens" to "5",
                    "roadtrip.vendor-rate-limit.aspira.refill-period" to "10s",
                ),
            )

        val aspira = config.vendorRateLimit.forVendor("aspira")
        assertEquals(5, aspira.capacity)
        assertEquals(5, aspira.refillTokens)
        assertEquals(Duration.ofSeconds(10), aspira.refillPeriod)
    }

    @Test
    fun `read path provider config rejects unknown availability provider ids`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                appConfig(mapOf("roadtrip.read-path.enabled-availability-providers" to "recgov,wat"))
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
            appConfig(
                mapOf(
                    "roadtrip.read-path.enabled-data-providers" to "",
                    "roadtrip.read-path.enabled-availability-providers" to "",
                ),
            ).readPathProviders

        assertEquals(emptySet(), config.enabledDataProviders)
        assertEquals(emptySet(), config.enabledAvailabilityProviders)
        assertEquals(false, config.isDataProviderEnabled("recgov"))
        assertEquals(false, config.isAvailabilityProviderEnabled("recgov"))
    }

    @Test
    fun `db config uses local defaults when properties are empty`() {
        val config = DbConfig.fromConfig(ConfigSection(emptyMap()).section("roadtrip.db"))

        assertEquals("jdbc:postgresql://localhost:5432/roadtrip", config.jdbcUrl)
        assertEquals("roadtrip", config.user)
        assertEquals("roadtrip", config.password)
    }

    @Test
    fun `db config reads property overrides`() {
        val config =
            DbConfig.fromConfig(
                ConfigSection(
                    mapOf(
                        "roadtrip.db.url" to "jdbc:postgresql://db.internal:5432/roadtrip",
                        "roadtrip.db.user" to "app",
                        "roadtrip.db.password" to "secret",
                    ),
                ).section("roadtrip.db"),
            )

        assertEquals("jdbc:postgresql://db.internal:5432/roadtrip", config.jdbcUrl)
        assertEquals("app", config.user)
        assertEquals("secret", config.password)
    }

    @Test
    fun `cache config rejects invalid durations`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                appConfig(mapOf("roadtrip.cache.route.ttl" to "forever"))
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
                appConfig(mapOf("roadtrip.cache.route.ttl" to "0s"))
            }

        assertEquals("roadtrip.cache.route.ttl must be positive", err.message)
    }

    @Test
    fun `slack config is null when token or channel is absent or blank`() {
        assertEquals(null, appConfig().slack)
        assertEquals(null, appConfig(mapOf("roadtrip.slack.bot-token" to "xoxb-x")).slack)
        assertEquals(null, appConfig(mapOf("roadtrip.slack.default-channel" to "#c")).slack)
        assertEquals(
            null,
            appConfig(mapOf("roadtrip.slack.bot-token" to "  ", "roadtrip.slack.default-channel" to "#c")).slack,
        )
    }

    @Test
    fun `slack config is populated and trimmed when both token and channel are set`() {
        val slack =
            appConfig(
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
            appConfig(
                mapOf(
                    "roadtrip.slack.bot-token" to "xoxb-a",
                    "roadtrip.slack.default-channel" to "#c",
                    "roadtrip.slack.signing-secret" to "  s3cr3t  ",
                ),
            ).slack
        assertEquals("s3cr3t", enabled?.signingSecret)

        val blank =
            appConfig(
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
        assertEquals(null, appConfig().grafana)
        assertEquals(null, appConfig(mapOf("roadtrip.grafana.root-url" to "  ")).grafana)
    }

    @Test
    fun `grafana root url is taken from properties with any trailing slash stripped`() {
        assertEquals(
            "http://localhost:3000/dash",
            appConfig(mapOf("roadtrip.grafana.root-url" to "http://localhost:3000/dash/")).grafana?.rootUrl,
        )
    }

    @Test
    fun `web app config is null when the host is unset (no hardcoded default)`() {
        assertEquals(null, appConfig().webApp)
        assertEquals(null, appConfig(mapOf("roadtrip.web.root-url" to "  ")).webApp)
    }

    @Test
    fun `web app root url is taken from properties with any trailing slash stripped`() {
        assertEquals(
            "https://roadtrip.floo.ca",
            appConfig(mapOf("roadtrip.web.root-url" to "https://roadtrip.floo.ca/")).webApp?.rootUrl,
        )
    }
}
