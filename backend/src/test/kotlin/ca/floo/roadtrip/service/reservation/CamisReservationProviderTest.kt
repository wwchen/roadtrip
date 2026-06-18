package ca.floo.roadtrip.service.reservation

import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.service.reservation.adapters.camis.CamisReservationProvider
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CamisReservationProviderTest {
    private val adapter = CamisReservationProvider()

    @Test
    fun `id and capabilities are honest about being unsupported`() {
        assertEquals(ReservationProviderId.CAMIS, adapter.id)
        assertEquals(false, adapter.capabilities.supportsAvailability)
        assertEquals(false, adapter.capabilities.supportsAlerts)
    }

    @Test
    fun `availability throws Unsupported`() {
        val ref = ProviderRef.Camis(facilityId = "AB-1")
        assertFailsWith<ReservationProviderError.Unsupported> {
            runBlocking {
                adapter.availability(
                    AvailabilityRequest(
                        ref = ref,
                        startDate = LocalDate.of(2026, 7, 14),
                        endDate = LocalDate.of(2026, 7, 21),
                    ),
                )
            }
        }
    }

    @Test
    fun `availableDates throws Unsupported`() {
        val ref = ProviderRef.Camis(facilityId = "AB-1")
        assertFailsWith<ReservationProviderError.Unsupported> {
            runBlocking {
                adapter.availableDates(
                    AvailableDatesRequest(
                        ref = ref,
                        startDate = LocalDate.of(2026, 7, 14),
                        endDate = LocalDate.of(2026, 7, 16),
                    ),
                )
            }
        }
    }
}
