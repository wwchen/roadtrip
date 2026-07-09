package ca.floo.roadtrip.service.availability.provider.adapters.aspira

import ca.floo.roadtrip.clients.aspira.AspiraAvailability
import ca.floo.roadtrip.clients.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.clients.aspira.AspiraException
import ca.floo.roadtrip.clients.aspira.AspiraOccupancy
import ca.floo.roadtrip.clients.aspira.AspiraResourceOccupancy
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.service.api.availabilityResponseFromObservations
import ca.floo.roadtrip.service.api.encodeAvailabilityJson
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
    fun `aspira resource availability narrows fetched map response to one resource`() =
        runBlocking {
            val client =
                fakeAspiraClient(
                    onFetch = { _, mapId, _, _ ->
                        AspiraAvailability(
                            mapId = mapId,
                            parkRollup = emptyList(),
                            byMapLink = emptyMap(),
                            byResource = mapOf("-2147478966" to listOf(0, 0, 1)),
                        )
                    },
                )

            val dto =
                availabilityResponseFromObservations(
                    fetchAspiraResourceObservations(
                        client = client,
                        host = "camping.bcparks.ca",
                        mapId = -2147483516,
                        resourceId = "-2147478966",
                        reservableVendor = "aspira_bc",
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-03"),
                    ),
                )

            assertEquals("site:aspira_bc:-2147478966", dto.reservableId)
            assertEquals(AvailabilityStatus.AVAILABLE, dto.availability[0].status)
            assertEquals(AvailabilityStatus.AVAILABLE, dto.availability[1].status)
        }

    @Test
    fun `aspira campground availability emits available resource ids when resources are present`() =
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
                        reservableVendor = "aspira_bc",
                    ),
                )

            assertEquals(AvailabilityStatus.AVAILABLE, dto.availability.single().status)
            assertEquals(
                listOf("site:aspira_bc:-2147478966", "site:aspira_bc:-2147478967"),
                dto.availability.single().availableReservableIds,
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
                        reservables =
                            listOf(
                                AspiraCatalogReservable("site:aspira_wa:a", "a", -101),
                                AspiraCatalogReservable("site:aspira_wa:b", "b", -101),
                                AspiraCatalogReservable("site:aspira_wa:c", "c", -202),
                                AspiraCatalogReservable("site:aspira_wa:missing", "missing", -202),
                            ),
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-03"),
                    ),
                )

            assertEquals((-999).toString(), dto.mapId)
            assertEquals(
                listOf("site:aspira_wa:a", "site:aspira_wa:c"),
                dto.availability[0].availableReservableIds,
            )
            assertEquals(4, dto.availability[0].reservableStatuses!!.size)
            assertEquals(4, dto.availability[1].reservableStatuses!!.size)
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
                        reservables =
                            listOf(
                                AspiraCatalogReservable("site:aspira_pc:available", "available", -101),
                                AspiraCatalogReservable("site:aspira_pc:unavailable", "unavailable", -101),
                                AspiraCatalogReservable("site:aspira_pc:blocked", "blocked", -101),
                            ),
                        startDate = LocalDate.parse("2026-07-09"),
                        endDate = LocalDate.parse("2026-07-10"),
                    ),
                )

            assertEquals(listOf("site:aspira_pc:available"), dto.availability.single().availableReservableIds)
            assertEquals(
                mapOf(
                    "site:aspira_pc:available" to AvailabilityStatus.AVAILABLE,
                    "site:aspira_pc:blocked" to AvailabilityStatus.RESERVED,
                    "site:aspira_pc:unavailable" to AvailabilityStatus.RESERVED,
                ),
                dto.availability.single().reservableStatuses,
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
                        reservables =
                            listOf(
                                AspiraCatalogReservable("site:aspira_pc:100", "100", -101),
                            ),
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-02"),
                    ),
                )

            assertEquals(AvailabilityStatus.UNKNOWN, dto.availability.single().status)
            assertEquals(
                mapOf("site:aspira_pc:100" to AvailabilityStatus.UNKNOWN),
                dto.availability.single().reservableStatuses,
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
                        reservables =
                            listOf(
                                AspiraCatalogReservable("site:aspira_pc:100", "100", -101),
                                AspiraCatalogReservable("site:aspira_pc:200", "200", -101),
                                AspiraCatalogReservable("site:aspira_pc:300", "300", -101),
                            ),
                        today = LocalDate.parse("2026-06-17"),
                        days = 2,
                    ),
                )

            assertEquals(listOf("site:aspira_pc:100"), dto.availability[0].availableReservableIds)
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
