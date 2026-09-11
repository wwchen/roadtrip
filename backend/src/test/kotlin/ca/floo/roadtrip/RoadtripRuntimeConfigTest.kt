package ca.floo.roadtrip

import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.config.ApplicationProperties
import ca.floo.roadtrip.config.AuthConfig
import ca.floo.roadtrip.config.AvailabilityConfig
import ca.floo.roadtrip.config.ConfigSection
import ca.floo.roadtrip.config.IngestConfig
import ca.floo.roadtrip.config.ReadPathProviderConfig
import ca.floo.roadtrip.di.notificationTriggerKinds
import ca.floo.roadtrip.di.validateReadPathDataProviders
import ca.floo.roadtrip.fixtures.configResourceClassLoader
import ca.floo.roadtrip.model.metadata.registry.EtlEntry
import ca.floo.roadtrip.model.metadata.registry.PoiDataEntry
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.availability.AvailabilityTriggerKinds
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RoadtripRuntimeConfigTest {
    @Test
    fun `read path data source validation accepts registry and detail source keys`() {
        validateReadPathDataProviders(
            providers =
                ReadPathProviderConfig(
                    enabledDataProviders =
                        setOf(
                            "recgov-campgrounds",
                            "recgov",
                            "campflare",
                            "aspira",
                            "bcparks-strapi",
                            "reserveamerica",
                            "reservecalifornia",
                            "tesla_supercharger",
                            "planet_fitness_location",
                        ),
                    enabledAvailabilityProviders = emptySet(),
                ),
            registry =
                registryWith(
                    "recgov-campgrounds",
                    "campflare-campgrounds",
                    "aspira-pc-campgrounds",
                    "aspira-bc-campgrounds",
                    "reserveamerica-ny-campgrounds",
                    "reservecalifornia-campgrounds",
                ),
        )
    }

    @Test
    fun `read path data source validation rejects unknown keys`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                validateReadPathDataProviders(
                    providers =
                        ReadPathProviderConfig(
                            enabledDataProviders = setOf("recgov", "recgvo"),
                            enabledAvailabilityProviders = emptySet(),
                        ),
                    registry = registryWith("recgov-campgrounds"),
                )
            }

        assertEquals(
            "roadtrip.read-path.enabled-data-providers contains unknown provider(s): " +
                "[recgvo]. Expected one of: [planet_fitness_location, recgov, recgov-campgrounds, tesla_supercharger].",
            err.message,
        )
    }

    @Test
    fun `notification trigger kinds include email only when email transport is configured`() {
        assertEquals(
            listOf(AvailabilityTriggerKinds.SLACK_NOTIFY, AvailabilityTriggerKinds.EMAIL_NOTIFY),
            notificationTriggerKinds(emailConfigured = true),
        )
        assertEquals(
            listOf(AvailabilityTriggerKinds.SLACK_NOTIFY),
            notificationTriggerKinds(emailConfigured = false),
        )
    }

    @Test
    fun `the campsite IP rate limit defaults to the literal it replaced`() {
        assertEquals(30, availabilityConfig(emptyMap()).campsite.ipRateLimitPerMinute)
    }

    @Test
    fun `the campsite IP rate limit is tunable`() {
        assertEquals(
            7,
            availabilityConfig(mapOf("roadtrip.availability.campsite.ip-rate-limit-per-minute" to "7"))
                .campsite.ipRateLimitPerMinute,
        )
    }

    @Test
    fun `a campsite IP rate limit below one is refused at boot`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                availabilityConfig(mapOf("roadtrip.availability.campsite.ip-rate-limit-per-minute" to "0"))
            }

        assertEquals("campsite ip-rate-limit-per-minute must be >= 1 (got 0)", err.message)
    }

    @Test
    fun `the shipped availability yaml states the campsite limit beside the bulk one`() {
        assertEquals(
            30,
            AppConfig
                .fromProperties(ApplicationProperties.load())
                .availability.campsite.ipRateLimitPerMinute,
        )
    }

    @Test
    fun `allowed auth connections default to the literal they replaced`() {
        assertEquals(setOf("google-oauth2"), authConfig(emptyMap())!!.allowedConnections)
    }

    @Test
    fun `allowed auth connections are configurable per environment`() {
        assertEquals(
            setOf("google-oauth2", "windowslive"),
            authConfig(mapOf("roadtrip.auth.allowed-connections" to "google-oauth2,windowslive"))!!.allowedConnections,
        )
    }

    @Test
    fun `an absent allowed-connections key keeps the default, an empty one forwards nothing`() {
        assertEquals(
            setOf("google-oauth2"),
            authConfig(emptyMap())!!.allowedConnections,
            "an absent key cannot mean anything but the default",
        )
        assertEquals(
            emptySet<String>(),
            authConfig(mapOf("roadtrip.auth.allowed-connections" to ""))!!.allowedConnections,
            "a present but empty key is an operator saying 'forward nothing'",
        )
    }

    @Test
    fun `the stale ingest run threshold defaults to the literal it replaced`() {
        assertEquals(Duration.ofMinutes(30), ingestConfig(emptyMap()).staleRunAfter)
    }

    @Test
    fun `the stale ingest run threshold is tunable`() {
        assertEquals(
            Duration.ofHours(2),
            ingestConfig(mapOf("roadtrip.ingest.stale-run-after" to "2h")).staleRunAfter,
        )
    }

    @Test
    fun `the shipped yaml states the stale ingest run threshold`() {
        assertEquals(
            Duration.ofMinutes(30),
            AppConfig.fromProperties(ApplicationProperties.load()).ingest.staleRunAfter,
        )
    }

    @Test
    fun `the shipped auth yaml states the allowed connection list`() {
        assertEquals(setOf("google-oauth2"), shippedAppConfig().auth!!.allowedConnections)
    }

    @Test
    fun `a two-value yaml list reaches the config as both connections`() {
        // The shipped list has one entry, which equals the code default — so
        // only a longer list proves the yaml path is what is read.
        val properties =
            ApplicationProperties.load(
                env = emptyMap(),
                classLoader =
                    configResourceClassLoader(
                        "application.yaml" to
                            """
                            roadtrip:
                              auth:
                                provider: oidc
                                providers:
                                  oidc:
                                    issuer: https://test.example
                                    client-id: test-client
                                    client-secret: test-secret
                                allowed-connections:
                                  - google-oauth2
                                  - windowslive
                            """.trimIndent(),
                        "application-local.yaml" to "{}",
                    ),
            )

        assertEquals(
            setOf("google-oauth2", "windowslive"),
            AuthConfig.fromConfig(ConfigSection(properties).section("roadtrip").section("auth"))!!.allowedConnections,
        )
    }

    /**
     * The shipped config with the active vendor's credentials filled in, which
     * the environment supplies in production. Without them auth is disabled and
     * nothing under `roadtrip.auth` is read at all.
     */
    private fun shippedAppConfig(): AppConfig {
        val properties = ApplicationProperties.load()
        val provider = properties.getValue("roadtrip.auth.provider")
        return AppConfig.fromProperties(
            properties +
                mapOf(
                    "roadtrip.auth.providers.$provider.issuer" to "https://test.example",
                    "roadtrip.auth.providers.$provider.client-id" to "test-client",
                    "roadtrip.auth.providers.$provider.client-secret" to "test-secret",
                ),
        )
    }

    private fun availabilityConfig(overrides: Map<String, String>): AvailabilityConfig =
        AvailabilityConfig.fromConfig(
            ConfigSection(
                mapOf(
                    "roadtrip.availability.force-pull-cooldown" to "60s",
                    "roadtrip.availability.provider-cooldown" to "5m",
                ) + overrides,
            ).section("roadtrip").section("availability"),
        )

    private fun ingestConfig(overrides: Map<String, String>): IngestConfig =
        IngestConfig.fromConfig(ConfigSection(overrides).section("roadtrip").section("ingest"))

    private fun authConfig(overrides: Map<String, String>): AuthConfig? =
        AuthConfig.fromConfig(
            ConfigSection(
                mapOf(
                    "roadtrip.auth.provider" to "oidc",
                    "roadtrip.auth.providers.oidc.issuer" to "https://test.example",
                    "roadtrip.auth.providers.oidc.client-id" to "test-client",
                    "roadtrip.auth.providers.oidc.client-secret" to "test-secret",
                ) + overrides,
            ).section("roadtrip").section("auth"),
        )

    private fun registryWith(vararg sources: String): PoiRegistry =
        PoiRegistry(
            dataSources = emptyList(),
            poiData =
                sources.map { source ->
                    PoiDataEntry(
                        name = source,
                        category = "campground",
                        etls =
                            listOf(
                                EtlEntry(
                                    slug = source,
                                    adapter = adapterFor(source),
                                    args = argsFor(source),
                                ),
                            ),
                    )
                },
        )

    private fun adapterFor(source: String): String =
        when (source) {
            "recgov-campgrounds" -> "RecGovCampgroundsEtl"
            "campflare-campgrounds" -> "CampflareCampgroundsEtl"
            "aspira-pc-campgrounds" -> "AspiraCampgroundsEtl"
            "aspira-bc-campgrounds" -> "BcParksCampgroundsEtl"
            "reserveamerica-ny-campgrounds" -> "ReserveAmericaCampgroundsEtl"
            "reservecalifornia-campgrounds" -> "ReserveCaliforniaCampgroundsEtl"
            else -> "TestEtl"
        }

    private fun argsFor(source: String): Map<String, String> =
        when (source) {
            "aspira-pc-campgrounds" -> mapOf("host" to "reservation.pc.gc.ca")
            "aspira-bc-campgrounds" -> mapOf("host" to "camping.bcparks.ca")
            "reserveamerica-ny-campgrounds" ->
                mapOf(
                    "contract" to "NY",
                    "host" to "newyorkstateparks.reserveamerica.com",
                    "provider" to "reserveamerica",
                )
            else -> emptyMap()
        }
}
