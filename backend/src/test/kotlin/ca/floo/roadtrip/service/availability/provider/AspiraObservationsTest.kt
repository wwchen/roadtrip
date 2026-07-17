package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.aspira.AspiraAvailability
import ca.floo.roadtrip.client.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.client.aspira.AspiraOccupancy
import ca.floo.roadtrip.client.aspira.AspiraResourceOccupancy
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.service.api.availabilityResponseFromObservations
import ca.floo.roadtrip.service.api.encodeAvailabilityJson
import ca.floo.roadtrip.support.AspiraException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class AspiraObservationsTest {
    @Test
    fun `aspira upstream mapper uses availability error dto renderer`() {
        val (status, error) = mapAspiraUpstreamError(AspiraException("WAF challenge", httpStatus = 503))
        val json = Json.parseToJsonElement(encodeAvailabilityJson(error)).jsonObject

        assertEquals(503, status.value)
        assertEquals("error", json["state"]!!.jsonPrimitive.content)
        assertEquals("upstream_blocked", json["error"]!!.jsonPrimitive.content)
        assertEquals(503, json["upstream_status"]!!.jsonPrimitive.int)
    }

    @Test
    fun `aspira campground availability stays unkeyed without catalog campsite ids`() =
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
                                    "-2147478966" to listOf(0, 0),
                                    "-2147478967" to listOf(0, 1),
                                ),
                        )
                    },
                )

            val dto =
                availabilityResponseFromObservations(
                    fetchAspiraAvailabilityObservations(
                        client = client,
                        host = "camping.bcparks.ca",
                        mapId = -2147483516,
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-02"),
                        campsiteVendor = "aspira_bc",
                    ),
                )

            assertEquals(AvailabilityStatus.UNKNOWN, dto.availability.single().status)
            assertEquals(
                emptyList(),
                dto.availability.single().availableCampsiteIds,
            )
        }

    @Test
    fun `aspira catalog availability aggregates linked resources across child maps`() =
        runBlocking {
            val client =
                fakeAspiraClient(
                    onFetch = { _, mapId, _, _ ->
                        when (mapId) {
                            -101 ->
                                AspiraAvailability(
                                    mapId = mapId,
                                    parkRollup = emptyList(),
                                    byMapLink = emptyMap(),
                                    byResource =
                                        mapOf(
                                            "a" to listOf(0, 0),
                                            "b" to listOf(1, 0),
                                        ),
                                )
                            -202 ->
                                AspiraAvailability(
                                    mapId = mapId,
                                    parkRollup = emptyList(),
                                    byMapLink = emptyMap(),
                                    byResource = mapOf("c" to listOf(0, 1)),
                                )
                            else ->
                                AspiraAvailability(
                                    mapId = mapId,
                                    parkRollup = emptyList(),
                                    byMapLink = emptyMap(),
                                    byResource = emptyMap(),
                                )
                        }
                    },
                )

            val dto =
                availabilityResponseFromObservations(
                    fetchAspiraCatalogObservations(
                        client = client,
                        host = "washington.goingtocamp.com",
                        parentMapId = -999,
                        campsites =
                            listOf(
                                AspiraCatalogCampsite(1, "a", -101),
                                AspiraCatalogCampsite(2, "b", -101),
                                AspiraCatalogCampsite(3, "c", -202),
                                AspiraCatalogCampsite(4, "missing", -202),
                            ),
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-03"),
                    ),
                )

            assertEquals((-999).toString(), dto.mapId)
            assertEquals(
                listOf(1L, 3L),
                dto.availability[0].availableCampsiteIds,
            )
            assertEquals(4, dto.availability[0].campsiteStatuses!!.size)
            assertEquals(4, dto.availability[1].campsiteStatuses!!.size)
        }

    @Test
    fun `aspira catalog availability uses resource availability code family`() =
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
                                    "available" to listOf(0),
                                    "unavailable" to listOf(1),
                                    "blocked" to listOf(4),
                                ),
                        )
                    },
                )

            val dto =
                availabilityResponseFromObservations(
                    fetchAspiraCatalogObservations(
                        client = client,
                        host = "reservation.pc.gc.ca",
                        parentMapId = -999,
                        campsites =
                            listOf(
                                AspiraCatalogCampsite(1, "available", -101),
                                AspiraCatalogCampsite(2, "unavailable", -101),
                                AspiraCatalogCampsite(3, "blocked", -101),
                            ),
                        startDate = LocalDate.parse("2026-07-09"),
                        endDate = LocalDate.parse("2026-07-10"),
                    ),
                )

            assertEquals(listOf(1L), dto.availability.single().availableCampsiteIds)
            assertEquals(
                mapOf(
                    1L to AvailabilityStatus.AVAILABLE,
                    2L to AvailabilityStatus.RESERVED,
                    3L to AvailabilityStatus.RESERVED,
                ),
                dto.availability.single().campsiteStatuses,
            )
        }

    @Test
    fun `aspira catalog missing resource day is unknown with reservable status`() =
        runBlocking {
            val client =
                fakeAspiraClient(
                    onFetch = { _, mapId, _, _ ->
                        AspiraAvailability(
                            mapId = mapId,
                            parkRollup = emptyList(),
                            byMapLink = emptyMap(),
                            byResource = emptyMap(),
                        )
                    },
                )

            val dto =
                availabilityResponseFromObservations(
                    fetchAspiraCatalogObservations(
                        client = client,
                        host = "reservation.pc.gc.ca",
                        parentMapId = -999,
                        campsites =
                            listOf(
                                AspiraCatalogCampsite(100, "100", -101),
                            ),
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-02"),
                    ),
                )

            assertEquals(AvailabilityStatus.UNKNOWN, dto.availability.single().status)
            assertEquals(
                mapOf(100L to AvailabilityStatus.UNKNOWN),
                dto.availability.single().campsiteStatuses,
            )
        }

    @Test
    fun `aspira catalog availability uses occupancy search availability`() =
        runBlocking {
            val client =
                fakeAspiraClient(
                    onFetchOccupancy = { _, resourceLocationId, start, _ ->
                        val rows =
                            when (start) {
                                LocalDate.parse("2026-06-17") ->
                                    listOf(
                                        AspiraResourceOccupancy(resourceId = 100, availability = 0),
                                        AspiraResourceOccupancy(resourceId = 200, availability = 2),
                                        AspiraResourceOccupancy(resourceId = 300, availability = 0, filtered = true),
                                    )
                                else ->
                                    listOf(
                                        AspiraResourceOccupancy(resourceId = 100, availability = 2),
                                        AspiraResourceOccupancy(resourceId = 200, availability = 2),
                                        AspiraResourceOccupancy(resourceId = 300, availability = 2),
                                    )
                            }
                        AspiraOccupancy(
                            resourceLocationId = resourceLocationId,
                            resourceOccupancy = rows,
                        )
                    },
                )

            val dto =
                availabilityResponseFromObservations(
                    fetchAspiraCatalogOccupancyObservations(
                        client = client,
                        host = "reservation.pc.gc.ca",
                        parentMapId = -999,
                        resourceLocationId = -123,
                        campsites =
                            listOf(
                                AspiraCatalogCampsite(100, "100", -101),
                                AspiraCatalogCampsite(200, "200", -101),
                                AspiraCatalogCampsite(300, "300", -101),
                            ),
                        today = LocalDate.parse("2026-06-17"),
                        days = 2,
                    ),
                )

            assertEquals(listOf(100L), dto.availability[0].availableCampsiteIds)
            assertEquals(AvailabilityStatus.RESERVED, dto.availability[1].status)
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
