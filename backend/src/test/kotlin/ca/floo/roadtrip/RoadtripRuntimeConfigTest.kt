package ca.floo.roadtrip

import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.config.ApplicationProperties
import ca.floo.roadtrip.config.AuthConfig
import ca.floo.roadtrip.config.AvailabilityConfig
import ca.floo.roadtrip.config.ConfigSection
import ca.floo.roadtrip.config.ReadPathProviderConfig
import ca.floo.roadtrip.di.notificationTriggerKinds
import ca.floo.roadtrip.di.validateReadPathDataProviders
import ca.floo.roadtrip.model.metadata.registry.EtlEntry
import ca.floo.roadtrip.model.metadata.registry.PoiDataEntry
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.availability.AvailabilityTriggerKinds
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

    private fun availabilityConfig(overrides: Map<String, String>): AvailabilityConfig =
        AvailabilityConfig.fromConfig(
            ConfigSection(
                mapOf(
                    "roadtrip.availability.force-pull-cooldown" to "60s",
                    "roadtrip.availability.provider-cooldown" to "5m",
                ) + overrides,
            ).section("roadtrip").section("availability"),
        )

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
