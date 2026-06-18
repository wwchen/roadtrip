package ca.floo.roadtrip.models.metadata.aspira

import ca.floo.roadtrip.service.api.AvailabilityStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AspiraStatusTest {
    @Test
    fun `available codes map to available`() {
        assertEquals(AvailabilityStatus.AVAILABLE, AspiraStatus.classify(AspiraStatus.AVAILABLE))
        assertEquals(AvailabilityStatus.AVAILABLE, AspiraStatus.classify(AspiraStatus.LIMITED))
    }

    @Test
    fun `availability-shaped codes map to available`() {
        assertEquals(AvailabilityStatus.AVAILABLE, AspiraStatus.classify(AspiraStatus.PARTIAL))
        assertEquals(AvailabilityStatus.AVAILABLE, AspiraStatus.classify(AspiraStatus.MIXED))
        assertEquals(AvailabilityStatus.AVAILABLE, AspiraStatus.classify(AspiraStatus.MOSTLY_BOOKED))
    }

    @Test
    fun `unavailable maps to closed and no data maps to unknown`() {
        assertEquals(AvailabilityStatus.CLOSED, AspiraStatus.classify(AspiraStatus.UNAVAILABLE))
        assertEquals(AvailabilityStatus.UNKNOWN, AspiraStatus.classify(AspiraStatus.NO_DATA))
    }

    @Test
    fun `unknown code maps to unknown`() {
        assertEquals(AvailabilityStatus.UNKNOWN, AspiraStatus.classify(99))
        assertEquals(AvailabilityStatus.UNKNOWN, AspiraStatus.classify(-1))
    }
}
