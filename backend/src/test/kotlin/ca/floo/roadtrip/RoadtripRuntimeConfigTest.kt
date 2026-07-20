package ca.floo.roadtrip

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
                    "booking_horizon_days" to "270",
                    "provider" to "reserveamerica",
                )
            else -> emptyMap()
        }
}
