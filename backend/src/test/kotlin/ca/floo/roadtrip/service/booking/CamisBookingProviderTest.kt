package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.service.booking.adapters.camis.CamisBookingProvider
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CamisBookingProviderTest {
    private val adapter = CamisBookingProvider()

    @Test
    fun `id and capabilities are honest about being unsupported`() {
        assertEquals(BookingProviderId.CAMIS, adapter.id)
        assertEquals(false, adapter.capabilities.supportsAvailability)
        assertEquals(false, adapter.capabilities.supportsAlerts)
        assertEquals(false, adapter.capabilities.supportsAutoBook)
    }

    @Test
    fun `availability throws Unsupported`() {
        val ref = ProviderRef.Camis(facilityId = "AB-1")
        assertFailsWith<BookingProviderError.Unsupported> {
            runBlocking {
                adapter.availability(
                    AvailabilityRequest(ref = ref, start = LocalDate.of(2026, 7, 14), days = 7),
                )
            }
        }
    }

    @Test
    fun `availableDates throws Unsupported`() {
        val ref = ProviderRef.Camis(facilityId = "AB-1")
        assertFailsWith<BookingProviderError.Unsupported> {
            runBlocking {
                adapter.availableDates(
                    AvailableDatesRequest(ref = ref, start = LocalDate.of(2026, 7, 14), nights = 2),
                )
            }
        }
    }
}
