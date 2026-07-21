package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.aspira.AspiraAvailability
import ca.floo.roadtrip.client.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.client.aspira.AspiraOccupancy
import ca.floo.roadtrip.client.aspira.AspiraResourceOccupancy
import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.service.api.availabilityResponseFromObservations
import ca.floo.roadtrip.service.api.encodeAvailabilityJson
import ca.floo.roadtrip.support.AspiraException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

private const val BC_PARKS_HOST = "camping.bcparks.ca"
private const val BC_PARKS_TEST_MAP_ID = -2147483460L
private const val BC_PARKS_TEST_CAMPSITE_ID = 415777L
private const val BC_PARKS_TEST_RESOURCE_ID = "-2147477118"

private const val PC_HOST = "reservation.pc.gc.ca"
private const val WA_HOST = "washington.goingtocamp.com"

class AspiraObservationsTest {
    private val tenants =
        mapOf(
            "pc" to AspiraTenant(host = PC_HOST, vendorCode = "aspira_pc", bookingHorizonDays = 365),
            "wa" to AspiraTenant(host = WA_HOST, vendorCode = "aspira_wa", bookingHorizonDays = 365),
            "bc" to
                AspiraTenant(
                    host = BC_PARKS_HOST,
                    vendorCode = "aspira_bc",
                    bookingHorizonDays = 365,
                    mapResourceCodeFamily = AspiraMapResourceCodeFamily.MAP,
                ),
        )

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

            val provider = AspiraAvailabilityProvider(tenants, client, enabled = true)
            val dto =
                availabilityResponseFromObservations(
                    provider.availability(
                        campground = aspiraCampground("bc", BC_PARKS_TEST_MAP_ID),
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-02"),
                    ),
                )

            assertEquals(AvailabilityStatus.UNKNOWN, dto.availability.single().status)
            assertEquals(emptyList(), dto.availability.single().availableCampsiteIds)
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

            val provider = AspiraAvailabilityProvider(tenants, client, enabled = true)
            val campsites =
                listOf(
                    aspiraCampsite(1, "a"),
                    aspiraCampsite(2, "b"),
                    aspiraCampsite(3, "c"),
                    aspiraCampsite(4, "missing"),
                )
            val dto =
                availabilityResponseFromObservations(
                    provider.catalogAvailability(
                        campground = aspiraCampground("wa", -999),
                        campsites = campsites,
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-03"),
                    ),
                )

            assertEquals((-999).toString(), dto.mapId)
            assertEquals(listOf(1L, 3L), dto.availability[0].availableCampsiteIds)
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

            val provider = AspiraAvailabilityProvider(tenants, client, enabled = true)
            val campsites =
                listOf(
                    aspiraCampsite(1, "available"),
                    aspiraCampsite(2, "unavailable"),
                    aspiraCampsite(3, "blocked"),
                )
            val dto =
                availabilityResponseFromObservations(
                    provider.catalogAvailability(
                        campground = aspiraCampground("pc", -999),
                        campsites = campsites,
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
    fun `bc parks catalog availability uses map code family for resource rows`() =
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
                                    BC_PARKS_TEST_RESOURCE_ID to listOf(3, 5, 0),
                                ),
                        )
                    },
                )

            val provider = AspiraAvailabilityProvider(tenants, client, enabled = true)
            val campsites =
                listOf(
                    aspiraCampsite(
                        BC_PARKS_TEST_CAMPSITE_ID,
                        BC_PARKS_TEST_RESOURCE_ID,
                        dataProviderRef =
                            DataProviderRef.BcParksCampsite(
                                tenant = "bc",
                                resourceLocationId = BC_PARKS_TEST_RESOURCE_ID.toLong(),
                            ),
                    ),
                )
            val dto =
                availabilityResponseFromObservations(
                    provider.catalogAvailability(
                        campground = aspiraCampground("bc", BC_PARKS_TEST_MAP_ID),
                        campsites = campsites,
                        startDate = LocalDate.parse("2026-07-20"),
                        endDate = LocalDate.parse("2026-07-23"),
                    ),
                )

            assertEquals(listOf(BC_PARKS_TEST_CAMPSITE_ID), dto.availability[0].availableCampsiteIds)
            assertEquals(AvailabilityStatus.CLOSED, dto.availability[1].status)
            assertEquals(AvailabilityStatus.UNKNOWN, dto.availability[2].status)
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

            val provider = AspiraAvailabilityProvider(tenants, client, enabled = true)
            val campsites = listOf(aspiraCampsite(100, "100"))
            val dto =
                availabilityResponseFromObservations(
                    provider.catalogAvailability(
                        campground = aspiraCampground("pc", -999),
                        campsites = campsites,
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
                    onFetchOccupancy = { _, _, start, _ ->
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
                            resourceLocationId = -123,
                            resourceOccupancy = rows,
                        )
                    },
                )

            val provider = AspiraAvailabilityProvider(tenants, client, enabled = true, occupancyEnabled = true)
            val campsites =
                listOf(
                    aspiraCampsite(100, "100", DataProviderRef.AspiraCampsite(tenant = "pc", resourceLocationId = 100)),
                    aspiraCampsite(200, "200", DataProviderRef.AspiraCampsite(tenant = "pc", resourceLocationId = 200)),
                    aspiraCampsite(300, "300", DataProviderRef.AspiraCampsite(tenant = "pc", resourceLocationId = 300)),
                )
            val dto =
                availabilityResponseFromObservations(
                    provider.catalogAvailability(
                        campground = aspiraCampground("pc", -999, resourceLocationId = -123),
                        campsites = campsites,
                        startDate = LocalDate.parse("2026-06-17"),
                        endDate = LocalDate.parse("2026-06-19"),
                    ),
                )

            assertEquals(listOf(100L), dto.availability[0].availableCampsiteIds)
            assertEquals(AvailabilityStatus.RESERVED, dto.availability[1].status)
        }
}

private fun aspiraCampground(
    tenant: String,
    mapId: Long,
    resourceLocationId: Long? = null,
): Campground =
    Campground(
        id = 1L,
        name = "Test Aspira Campground",
        status = null,
        statusDescription = null,
        kind = null,
        shortDescription = null,
        mediumDescription = null,
        longDescription = null,
        location = JsonNull,
        defaultCampsiteSchedule = JsonNull,
        amenities = JsonNull,
        maxRvLength = null,
        maxTrailerLength = null,
        hasPullThroughSites = null,
        bigRigFriendly = null,
        reservationUrl = null,
        links = JsonNull,
        photos = JsonNull,
        alerts = JsonNull,
        price = JsonNull,
        cellService = JsonNull,
        management = JsonNull,
        contact = JsonNull,
        connections = JsonNull,
        metadata = JsonNull,
        sourcePayload = JsonNull,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        deletedAt = null,
        dataProviderRef =
            DataProviderRef.Aspira(transactionLocationId = mapId, mapId = mapId),
        bookingProvider = "aspira",
        bookingProviderRef =
            buildString {
                append("aspira:$tenant:$mapId")
                if (resourceLocationId != null) append(":$resourceLocationId")
            },
    )

private fun aspiraCampsite(
    id: Long,
    resourceId: String,
    dataProviderRef: DataProviderRef = DataProviderRef.AspiraCampsite(tenant = "pc", resourceLocationId = resourceId.toLongOrNull() ?: id),
): Campsite =
    campsiteFixture(
        id = id,
        vendor = "aspira",
        vendorId = resourceId,
        dataProviderRef = dataProviderRef,
        bookingProvider = "aspira",
        bookingProviderRef = resourceId,
    )

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
