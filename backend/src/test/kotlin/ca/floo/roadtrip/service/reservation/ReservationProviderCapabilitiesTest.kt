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
import ca.floo.roadtrip.service.reservation.adapters.aspira.AspiraReservationProvider
import ca.floo.roadtrip.service.reservation.adapters.aspira.AspiraTenant
import ca.floo.roadtrip.service.reservation.adapters.recgov.RecGovReservationProvider
import ca.floo.roadtrip.service.reservation.adapters.reserveamerica.ReserveAmericaReservationProvider
import ca.floo.roadtrip.service.reservation.adapters.reserveamerica.ReserveAmericaTenant
import ca.floo.roadtrip.service.reservation.adapters.reservecalifornia.ReserveCaliforniaReservationProvider
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ReservationProviderCapabilitiesTest {
    @Test
    fun `providers expose booking horizon and fetch window with explicit units`() {
        val recgov = RecGovReservationProvider(stubRecgovClient()).capabilities
        assertEquals(CapabilityLimit(6, CapabilityTimeUnit.MONTH), recgov.bookingHorizon)
        assertEquals(CapabilityLimit(1, CapabilityTimeUnit.MONTH), recgov.fetchWindowCap)

        val aspira =
            AspiraReservationProvider(
                tenant = AspiraTenant(host = "reservation.pc.gc.ca", vendorCode = "aspira_pc", bookingHorizonDays = 365),
                client = stubAspiraClient(),
            ).capabilities
        assertEquals(CapabilityLimit(365, CapabilityTimeUnit.DAY), aspira.bookingHorizon)
        assertEquals(CapabilityLimit(30, CapabilityTimeUnit.DAY), aspira.fetchWindowCap)

        val reserveAmerica =
            ReserveAmericaReservationProvider(
                tenant =
                    ReserveAmericaTenant(
                        source = "new-york-state-parks",
                        host = "newyorkstateparks.reserveamerica.com",
                        contractCode = "NY",
                        bookingHorizonDays = 270,
                    ),
                client = stubReserveAmericaClient(),
            ).capabilities
        assertEquals(CapabilityLimit(270, CapabilityTimeUnit.DAY), reserveAmerica.bookingHorizon)
        assertEquals(CapabilityLimit(14, CapabilityTimeUnit.DAY), reserveAmerica.fetchWindowCap)

        val reserveCalifornia = ReserveCaliforniaReservationProvider(stubReserveCaliforniaClient()).capabilities
        assertEquals(CapabilityLimit(183, CapabilityTimeUnit.DAY), reserveCalifornia.bookingHorizon)
        assertEquals(CapabilityLimit(30, CapabilityTimeUnit.DAY), reserveCalifornia.fetchWindowCap)
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
                    byResource = emptyMap(),
                )

            override suspend fun fetchOccupancy(
                host: String,
                resourceLocationId: Int,
                startDate: LocalDate,
                endDate: LocalDate,
            ): AspiraOccupancy = AspiraOccupancy(resourceLocationId = resourceLocationId, resourceOccupancy = emptyList())
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
