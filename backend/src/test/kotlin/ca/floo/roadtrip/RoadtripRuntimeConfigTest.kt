package ca.floo.roadtrip

import ca.floo.roadtrip.config.ReadPathProviderConfig
import ca.floo.roadtrip.di.notificationTriggerKinds
import ca.floo.roadtrip.di.validateReadPathDataSources
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
        validateReadPathDataSources(
            providers =
                ReadPathProviderConfig(
                    enabledDataSources =
                        setOf(
                            "federal-campgrounds",
                            "recgov",
                            "campflare",
                            "tesla_supercharger",
                            "planet_fitness_location",
                        ),
                    enabledAvailabilityProviders = emptySet(),
                ),
            registry = registryWith("federal-campgrounds", "campflare-campgrounds"),
        )
    }

    @Test
    fun `read path data source validation rejects unknown keys`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                validateReadPathDataSources(
                    providers =
                        ReadPathProviderConfig(
                            enabledDataSources = setOf("recgov", "recgvo"),
                            enabledAvailabilityProviders = emptySet(),
                        ),
                    registry = registryWith("federal-campgrounds"),
                )
            }

        assertEquals(
            "roadtrip.read-path.enabled-data-sources contains unknown source(s): " +
                "[recgvo]. Expected one of: [federal-campgrounds, planet_fitness_location, recgov, tesla_supercharger].",
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
                                ),
                            ),
                    )
                },
        )

    private fun adapterFor(source: String): String =
        when (source) {
            "federal-campgrounds" -> "RecGovCampgroundsEtl"
            "campflare-campgrounds" -> "CampflareCampgroundsEtl"
            else -> "TestEtl"
        }
}
