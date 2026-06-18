package ca.floo.roadtrip.service.reservation

import ca.floo.roadtrip.client.AspiraAvailability
import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.repo.CachedAspiraAvailability
import ca.floo.roadtrip.service.api.AvailabilityStatus
import ca.floo.roadtrip.service.api.availabilityDatesFromObservations
import ca.floo.roadtrip.service.reservation.adapters.aspira.AspiraReservationProvider
import ca.floo.roadtrip.service.reservation.adapters.aspira.AspiraTenant
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class AspiraReservationProviderTest {
    @Test
    fun `aspira catalog availability uses map resource status when resource location is known`() =
        runBlocking {
            val mapCache =
                CachedAspiraAvailability(
                    fetcher = { _, mapId, _, _ ->
                        AspiraAvailability(
                            mapId = mapId,
                            parkRollup = emptyList(),
                            byMapLink = emptyMap(),
                            byResource =
                                mapOf(
                                    "100" to List(7) { 1 },
                                ),
                        )
                    },
                )
            val adapter =
                AspiraReservationProvider(
                    tenant =
                        AspiraTenant(
                            host = "reservation.pc.gc.ca",
                            vendorCode = "aspira_pc",
                            bookingHorizonDays = 365,
                        ),
                    cache = mapCache,
                )

            val batch =
                adapter.catalogAvailability(
                    CatalogAvailabilityRequest(
                        ref =
                            ProviderRef.Aspira(
                                transactionLocationId = -2147483630,
                                mapId = -2147483388,
                                resourceLocationId = null,
                            ),
                        reservables =
                            listOf(
                                CatalogReservableRef(
                                    rid = "site:aspira_pc:100",
                                    vendorId = "100",
                                    mapId = -2147483615,
                                    resourceLocationId = -2147483624,
                                ),
                            ),
                        startDate = LocalDate.parse("2026-06-17"),
                        endDate = LocalDate.parse("2026-06-18"),
                    ),
                )

            val observation = batch.observations.single()
            assertEquals("site:aspira_pc:100", observation.reservableId)
            assertEquals(AvailabilityStatus.AVAILABLE, observation.status)
        }

    @Test
    fun `reservable availability stamps the tenant's vendor code on the reservable id`() =
        runBlocking {
            val cache =
                CachedAspiraAvailability(
                    fetcher = { _, mapId, _, _ ->
                        AspiraAvailability(
                            mapId = mapId,
                            parkRollup = emptyList(),
                            byMapLink = emptyMap(),
                            byResource = mapOf("-2147478966" to listOf(1)),
                        )
                    },
                )

            val cases =
                listOf(
                    "reservation.pc.gc.ca" to "aspira_pc",
                    "camping.bcparks.ca" to "aspira_bc",
                    "washington.goingtocamp.com" to "aspira_wa",
                )
            for ((host, vendor) in cases) {
                val tenant =
                    AspiraTenant(
                        host = host,
                        vendorCode = vendor,
                        bookingHorizonDays = 365,
                    )
                val adapter = AspiraReservationProvider(tenant = tenant, cache = cache)
                val batch =
                    adapter.reservableAvailability(
                        ReservableAvailabilityRequest(
                            ref =
                                ProviderRef.Aspira(
                                    transactionLocationId = -2147483648,
                                    mapId = -2147483516,
                                    resourceLocationId = -2147483515,
                                ),
                            vendorId = "-2147478966",
                            startDate = LocalDate.parse("2026-07-01"),
                            endDate = LocalDate.parse("2026-07-02"),
                        ),
                    )

                assertEquals("site:$vendor:-2147478966", batch.reservableId)
                assertEquals("site:$vendor:-2147478966", batch.observations.single().reservableId)
                assertEquals(AvailabilityStatus.AVAILABLE, batch.observations.single().status)
            }
        }

    @Test
    fun `available dates returns per-day facts without requiring a same-sub-area stay`() =
        runBlocking {
            val cache =
                CachedAspiraAvailability(
                    fetcher = { _, mapId, _, _ ->
                        AspiraAvailability(
                            mapId = mapId,
                            parkRollup = emptyList(),
                            byMapLink =
                                mapOf(
                                    "100" to listOf(1, 0),
                                    "101" to listOf(0, 1),
                                ),
                            byResource = emptyMap(),
                        )
                    },
                )
            val adapter =
                AspiraReservationProvider(
                    tenant =
                        AspiraTenant(
                            host = "reservation.pc.gc.ca",
                            vendorCode = "aspira_pc",
                            bookingHorizonDays = 365,
                        ),
                    cache = cache,
                )

            val batch =
                adapter.availability(
                    AvailabilityRequest(
                        ref =
                            ProviderRef.Aspira(
                                transactionLocationId = -2147483630,
                                mapId = -2147483388,
                                resourceLocationId = null,
                            ),
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-03"),
                    ),
                )

            val dates = availabilityDatesFromObservations(batch)
            assertEquals(listOf("2026-07-01", "2026-07-02"), dates)
        }
}
