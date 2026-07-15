package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.clients.aspira.AspiraAvailability
import ca.floo.roadtrip.clients.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.clients.aspira.AspiraOccupancy
import ca.floo.roadtrip.clients.aspira.AspiraResourceOccupancy
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.CatalogCampsiteRef
import ca.floo.roadtrip.models.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.api.availabilityDatesFromObservations
import ca.floo.roadtrip.service.availability.provider.adapters.aspira.AspiraAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.adapters.aspira.AspiraTenant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AspiraAvailabilityProviderTest {
    @Test
    fun `aspira advertises internal polling through catalog availability`() {
        val adapter =
            AspiraAvailabilityProvider(
                tenant =
                    AspiraTenant(
                        host = "reservation.pc.gc.ca",
                        vendorCode = "aspira_pc",
                        bookingHorizonDays = 365,
                    ),
                client = fakeAspiraClient(),
                enabled = true,
            )

        assertEquals(true, adapter.capabilities.supportsInternalPolling)
        assertEquals(365, adapter.capabilities.bookingHorizonDays)
        assertEquals(30, adapter.capabilities.maxPollWindowDays)
    }

    @Test
    fun `aspira catalog availability uses map resource status by default when resource location is known`() =
        runBlocking {
            var mapFetches = 0
            var occupancyFetches = 0
            val client =
                fakeAspiraClient(
                    onFetch = { _, mapId, start, end ->
                        mapFetches++
                        assertEquals(-2147483615, mapId)
                        assertEquals(LocalDate.parse("2026-06-17"), start)
                        assertEquals(LocalDate.parse("2026-06-18"), end)
                        AspiraAvailability(
                            mapId = mapId,
                            parkRollup = emptyList(),
                            byMapLink = emptyMap(),
                            byResource =
                                mapOf(
                                    "100" to listOf(0, 0),
                                    "200" to listOf(1, 1),
                                ),
                        )
                    },
                    onFetchOccupancy = { _, _, _, _ ->
                        occupancyFetches++
                        error("occupancy should not be fetched for the default per-day catalog path")
                    },
                )
            val adapter =
                AspiraAvailabilityProvider(
                    tenant =
                        AspiraTenant(
                            host = "reservation.pc.gc.ca",
                            vendorCode = "aspira_pc",
                            bookingHorizonDays = 365,
                        ),
                    client = client,
                    enabled = true,
                )

            val batch =
                adapter.catalogAvailability(
                    ref =
                        ProviderRef.Aspira(
                            transactionLocationId = -2147483630,
                            mapId = -2147483388,
                            resourceLocationId = -2147483624,
                        ),
                    campsites =
                        listOf(
                            CatalogCampsiteRef(
                                campsiteId = 100,
                                vendorId = "100",
                                mapId = -2147483615,
                                resourceLocationId = -2147483624,
                            ),
                            CatalogCampsiteRef(
                                campsiteId = 200,
                                vendorId = "200",
                                mapId = -2147483615,
                                resourceLocationId = -2147483624,
                            ),
                        ),
                    startDate = LocalDate.parse("2026-06-17"),
                    endDate = LocalDate.parse("2026-06-19"),
                )

            val byCampsiteId = batch.observations.filter { it.date == LocalDate.parse("2026-06-17") }.associateBy { it.campsiteId }
            assertEquals(1, mapFetches)
            assertEquals(0, occupancyFetches)
            assertEquals(AvailabilityStatus.AVAILABLE, byCampsiteId[100]!!.status)
            assertEquals(AvailabilityStatus.RESERVED, byCampsiteId[200]!!.status)
        }

    @Test
    fun `aspira catalog availability can opt into occupancy search`() =
        runBlocking {
            var mapFetches = 0
            val client =
                fakeAspiraClient(
                    onFetch = { _, _, _, _ ->
                        mapFetches++
                        error("map availability should not be fetched when occupancy is explicitly enabled")
                    },
                    onFetchOccupancy = { _, resourceLocationId, start, end ->
                        assertEquals(-2147483624, resourceLocationId)
                        assertEquals(LocalDate.parse("2026-06-17"), start)
                        assertEquals(LocalDate.parse("2026-06-18"), end)
                        AspiraOccupancy(
                            resourceLocationId = resourceLocationId,
                            resourceOccupancy =
                                listOf(
                                    AspiraResourceOccupancy(resourceId = 100, availability = 0),
                                    AspiraResourceOccupancy(resourceId = 200, availability = 2),
                                ),
                        )
                    },
                )
            val adapter =
                AspiraAvailabilityProvider(
                    tenant =
                        AspiraTenant(
                            host = "reservation.pc.gc.ca",
                            vendorCode = "aspira_pc",
                            bookingHorizonDays = 365,
                        ),
                    client = client,
                    enabled = true,
                    occupancyEnabled = true,
                )

            val batch =
                adapter.catalogAvailability(
                    ref =
                        ProviderRef.Aspira(
                            transactionLocationId = -2147483630,
                            mapId = -2147483388,
                            resourceLocationId = -2147483624,
                        ),
                    campsites =
                        listOf(
                            CatalogCampsiteRef(
                                campsiteId = 100,
                                vendorId = "100",
                                mapId = -2147483615,
                                resourceLocationId = -2147483624,
                            ),
                            CatalogCampsiteRef(
                                campsiteId = 200,
                                vendorId = "200",
                                mapId = -2147483615,
                                resourceLocationId = -2147483624,
                            ),
                        ),
                    startDate = LocalDate.parse("2026-06-17"),
                    endDate = LocalDate.parse("2026-06-18"),
                )

            val byCampsiteId = batch.observations.associateBy { it.campsiteId }
            assertEquals(0, mapFetches)
            assertEquals(AvailabilityStatus.AVAILABLE, byCampsiteId[100]!!.status)
            assertEquals(AvailabilityStatus.RESERVED, byCampsiteId[200]!!.status)
        }

    @Test
    fun `aspira catalog availability uses map resource status when occupancy is disabled`() =
        runBlocking {
            val client =
                fakeAspiraClient(
                    onFetch = { _, mapId, _, _ ->
                        AspiraAvailability(
                            mapId = mapId,
                            parkRollup = emptyList(),
                            byMapLink = emptyMap(),
                            byResource =
                                mapOf(
                                    "100" to List(7) { 0 },
                                ),
                        )
                    },
                )
            val adapter =
                AspiraAvailabilityProvider(
                    tenant =
                        AspiraTenant(
                            host = "reservation.pc.gc.ca",
                            vendorCode = "aspira_pc",
                            bookingHorizonDays = 365,
                        ),
                    client = client,
                    enabled = true,
                    occupancyEnabled = false,
                )

            val batch =
                adapter.catalogAvailability(
                    ref =
                        ProviderRef.Aspira(
                            transactionLocationId = -2147483630,
                            mapId = -2147483388,
                            resourceLocationId = null,
                        ),
                    campsites =
                        listOf(
                            CatalogCampsiteRef(
                                campsiteId = 100,
                                vendorId = "100",
                                mapId = -2147483615,
                                resourceLocationId = -2147483624,
                            ),
                        ),
                    startDate = LocalDate.parse("2026-06-17"),
                    endDate = LocalDate.parse("2026-06-18"),
                )

            val observation = batch.observations.single()
            assertEquals(100, observation.campsiteId)
            assertEquals(AvailabilityStatus.AVAILABLE, observation.status)
        }

    @Test
    fun `available dates returns per-day facts without requiring a same-sub-area stay`() =
        runBlocking {
            val client =
                fakeAspiraClient(
                    onFetch = { _, mapId, _, _ ->
                        AspiraAvailability(
                            mapId = mapId,
                            parkRollup = emptyList(),
                            byMapLink = emptyMap(),
                            byResource =
                                mapOf(
                                    "100" to listOf(1, 0),
                                    "101" to listOf(0, 1),
                                ),
                        )
                    },
                )
            val adapter =
                AspiraAvailabilityProvider(
                    tenant =
                        AspiraTenant(
                            host = "reservation.pc.gc.ca",
                            vendorCode = "aspira_pc",
                            bookingHorizonDays = 365,
                        ),
                    client = client,
                    enabled = true,
                )

            val batch =
                adapter.catalogAvailability(
                    ref =
                        ProviderRef.Aspira(
                            transactionLocationId = -2147483630,
                            mapId = -2147483388,
                            resourceLocationId = null,
                        ),
                    campsites =
                        listOf(
                            CatalogCampsiteRef(campsiteId = 100, vendorId = "100", mapId = -2147483388),
                            CatalogCampsiteRef(campsiteId = 101, vendorId = "101", mapId = -2147483388),
                        ),
                    startDate = LocalDate.parse("2026-07-01"),
                    endDate = LocalDate.parse("2026-07-03"),
                )

            val dates = availabilityDatesFromObservations(batch)
            assertEquals(listOf("2026-07-01", "2026-07-02"), dates)
        }

    @Test
    fun `booking url builds the tenant's goingtocamp deep link for the single night`() {
        val adapter =
            AspiraAvailabilityProvider(
                tenant = AspiraTenant(host = "washington.goingtocamp.com", vendorCode = "aspira_wa", bookingHorizonDays = 365),
                client = fakeAspiraClient(),
                enabled = true,
            )
        val reservable =
            CampsiteAvailabilityTarget(
                id = 1,
                vendor = "aspira_wa",
                vendorId = "-100",
                name = "A",
                loop = null,
                siteType = null,
                raw = null,
                // Site-level ref carries mapId + resourceLocationId; the parent supplies transactionLocationId.
                providerRef = Json.parseToJsonElement("""{"mapId":-2147483615,"resourceLocationId":-2147483624}"""),
            )
        val parentRef = ProviderRef.Aspira(transactionLocationId = -2147483630, mapId = -2147483388, resourceLocationId = -2147483624)

        val url = adapter.reservationUrl(reservable, parentRef, LocalDate.parse("2026-07-10"))!!

        assertTrue(url.startsWith("https://washington.goingtocamp.com/create-booking/results?"), url)
        assertTrue(url.contains("transactionLocationId=-2147483630"), url)
        assertTrue(url.contains("mapId=-2147483615"), url)
        assertTrue(url.contains("resourceLocationId=-2147483624"), url)
        assertTrue(url.contains("startDate=2026-07-10"), url)
        assertTrue(url.contains("endDate=2026-07-11"), url)
        assertTrue(url.contains("nights=1"), url)
    }
}

private fun fakeAspiraClient(
    onFetch: (suspend (String, Int, LocalDate, LocalDate) -> AspiraAvailability)? = null,
    onFetchOccupancy: (suspend (String, Int, LocalDate, LocalDate) -> AspiraOccupancy)? = null,
): AspiraAvailabilityClient =
    object : AspiraAvailabilityClient {
        override suspend fun fetch(
            host: String,
            mapId: Int,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AspiraAvailability =
            onFetch?.invoke(host, mapId, startDate, endDate)
                ?: error("fakeAspiraClient.fetch not stubbed")

        override suspend fun fetchOccupancy(
            host: String,
            resourceLocationId: Int,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AspiraOccupancy =
            onFetchOccupancy?.invoke(host, resourceLocationId, startDate, endDate)
                ?: error("fakeAspiraClient.fetchOccupancy not stubbed")
    }
