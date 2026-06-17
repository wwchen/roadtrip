package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.client.AspiraAvailability
import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.repo.CachedAspiraAvailability
import ca.floo.roadtrip.service.booking.adapters.aspira.AspiraBookingProvider
import ca.floo.roadtrip.service.booking.adapters.aspira.AspiraTenant
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class AspiraBookingProviderTest {
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
                AspiraBookingProvider(
                    tenant =
                        AspiraTenant(
                            host = "reservation.pc.gc.ca",
                            vendorCode = "aspira_pc",
                            bookingHorizonDays = 365,
                        ),
                    cache = mapCache,
                )

            val dto =
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
                        start = LocalDate.parse("2026-06-17"),
                        days = 1,
                        minNights = 7,
                    ),
                )

            assertEquals(1, dto.availability.single().availableCount)
            assertEquals(listOf("site:aspira_pc:100"), dto.availability.single().availableReservableIds)
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
                val adapter = AspiraBookingProvider(tenant = tenant, cache = cache)
                val dto =
                    adapter.reservableAvailability(
                        ReservableAvailabilityRequest(
                            ref =
                                ProviderRef.Aspira(
                                    transactionLocationId = -2147483648,
                                    mapId = -2147483516,
                                    resourceLocationId = -2147483515,
                                ),
                            vendorId = "-2147478966",
                            start = LocalDate.parse("2026-07-01"),
                            days = 1,
                        ),
                    )

                assertEquals("site:$vendor:-2147478966", dto.reservableId)
                assertEquals("available", dto.availability.single().status)
            }
        }
}
