package ca.floo.roadtrip.service.reservation

import ca.floo.roadtrip.clients.aspira.AspiraAvailability
import ca.floo.roadtrip.clients.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.clients.aspira.AspiraOccupancy
import ca.floo.roadtrip.clients.recgov.Campsite
import ca.floo.roadtrip.clients.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.clients.reservecalifornia.ReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.clients.reservecalifornia.ReserveCaliforniaGridAvailability
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.reservation.adapters.aspira.AspiraReservationProvider
import ca.floo.roadtrip.service.reservation.adapters.aspira.AspiraTenant
import ca.floo.roadtrip.service.reservation.adapters.recgov.RecGovReservationProvider
import ca.floo.roadtrip.service.reservation.adapters.reserveamerica.ReserveAmericaReservationProvider
import ca.floo.roadtrip.service.reservation.adapters.reserveamerica.ReserveAmericaTenant
import ca.floo.roadtrip.service.reservation.adapters.reservecalifornia.ReserveCaliforniaReservationProvider
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvailabilityClientContractTest {
    @Test
    fun `all reservation vendor adapters implement shared availability client contract`() {
        val clients: List<AvailabilityClient> =
            listOf(
                RecGovReservationProvider(client = stubRecgovClient()),
                AspiraReservationProvider(
                    tenant =
                        AspiraTenant(
                            host = "reservation.pc.gc.ca",
                            vendorCode = "aspira_pc",
                            bookingHorizonDays = 365,
                        ),
                    client = stubAspiraClient(),
                ),
                ReserveAmericaReservationProvider(
                    tenant =
                        ReserveAmericaTenant(
                            source = "new-york-state-parks",
                            host = "newyorkstateparks.reserveamerica.com",
                            contractCode = "NY",
                            bookingHorizonDays = 270,
                        ),
                    client = stubReserveAmericaClient(),
                ),
                ReserveCaliforniaReservationProvider(client = stubReserveCaliforniaClient()),
            )

        assertEquals(4, clients.size)
        assertDirectAvailabilityClient(RecGovReservationProvider::class.java)
        assertDirectAvailabilityClient(AspiraReservationProvider::class.java)
        assertDirectAvailabilityClient(ReserveAmericaReservationProvider::class.java)
        assertDirectAvailabilityClient(ReserveCaliforniaReservationProvider::class.java)
    }

    @Test
    fun `shared availability client accepts direct arguments instead of request wrappers`() =
        runBlocking {
            val client: AvailabilityClient = RecGovReservationProvider(client = stubRecgovClient())
            val startDate = LocalDate.parse("2026-07-01")
            val endDate = LocalDate.parse("2026-07-02")

            val availability =
                client.availability(
                    ref = ProviderRef.RecGov("232447"),
                    startDate = startDate,
                    endDate = endDate,
                )
            val catalog =
                client.catalogAvailability(
                    ref = ProviderRef.RecGov("232447"),
                    reservables = emptyList(),
                    startDate = startDate,
                    endDate = endDate,
                )
            val reservable =
                client.reservableAvailability(
                    ref = ProviderRef.RecGov("232447"),
                    vendorId = "330257",
                    startDate = startDate,
                    endDate = endDate,
                )

            assertEquals("recgov", availability.provider)
            assertEquals("recgov", catalog.provider)
            assertEquals("site:recgov:330257", reservable.reservableId)
        }

    private fun assertDirectAvailabilityClient(type: Class<*>) {
        assertTrue(
            type.interfaces.contains(AvailabilityClient::class.java),
            "${type.simpleName} should declare AvailabilityClient directly, not only inherit it through ReservationProvider",
        )
    }

    private fun stubRecgovClient(): RecGovAvailabilityClient =
        object : RecGovAvailabilityClient {
            override suspend fun fetchMonth(
                campgroundId: String,
                monthStart: String,
            ): Map<String, Campsite> = emptyMap()
        }

    private fun stubAspiraClient(): AspiraAvailabilityClient =
        object : AspiraAvailabilityClient {
            override suspend fun fetch(
                host: String,
                mapId: Int,
                startDate: LocalDate,
                endDate: LocalDate,
            ): AspiraAvailability =
                AspiraAvailability(
                    mapId = mapId,
                    parkRollup = emptyList(),
                    byMapLink = emptyMap(),
                )

            override suspend fun fetchOccupancy(
                host: String,
                resourceLocationId: Int,
                startDate: LocalDate,
                endDate: LocalDate,
            ): AspiraOccupancy = AspiraOccupancy(resourceLocationId = resourceLocationId)
        }

    private fun stubReserveAmericaClient(): ReserveAmericaAvailabilityClient =
        ReserveAmericaAvailabilityClient { _, contractCode, parkId, startDate, endDate ->
            ReserveAmericaAvailability(
                contractCode = contractCode,
                parkId = parkId,
                startDate = startDate,
                endDate = endDate,
                observedAt = Instant.EPOCH,
                statuses = emptyMap(),
            )
        }

    private fun stubReserveCaliforniaClient(): ReserveCaliforniaAvailabilityClient =
        object : ReserveCaliforniaAvailabilityClient {
            override suspend fun fetchGrid(
                facilityId: Long,
                startDate: LocalDate,
                endDate: LocalDate,
                minDate: LocalDate,
                maxDate: LocalDate,
            ): ReserveCaliforniaGridAvailability =
                ReserveCaliforniaGridAvailability(
                    facilityId = facilityId,
                    observedAt = Instant.EPOCH,
                    statuses = emptyMap(),
                )
        }
}
