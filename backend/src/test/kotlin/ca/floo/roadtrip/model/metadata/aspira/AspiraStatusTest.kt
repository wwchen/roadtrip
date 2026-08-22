package ca.floo.roadtrip.model.metadata.aspira

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AspiraStatusTest {
    @Test
    fun `zero is the bookable code`() {
        assertEquals(AvailabilityStatus.AVAILABLE, AspiraStatus.classify(AspiraStatus.AVAILABLE))
    }

    @Test
    fun `closed code maps to closed`() {
        assertEquals(AvailabilityStatus.CLOSED, AspiraStatus.classify(AspiraStatus.CLOSED))
    }

    @Test
    fun `other nonzero codes are not bookable`() {
        assertEquals(AvailabilityStatus.RESERVED, AspiraStatus.classify(AspiraStatus.UNAVAILABLE))
        assertEquals(AvailabilityStatus.RESERVED, AspiraStatus.classify(4))
        assertEquals(AvailabilityStatus.RESERVED, AspiraStatus.classify(99))
    }

    @Test
    fun `missing code sentinel maps to unknown`() {
        assertEquals(AvailabilityStatus.UNKNOWN, AspiraStatus.classify(AspiraStatus.UNKNOWN))
        assertEquals(AvailabilityStatus.UNKNOWN, AspiraStatus.classifyOccupancy(AspiraStatus.UNKNOWN))
    }

    @Test
    fun `occupancy rows cannot tell a booked site from a closed one`() {
        assertEquals(AvailabilityStatus.AVAILABLE, AspiraStatus.classifyOccupancy(AspiraStatus.AVAILABLE))
        assertEquals(AvailabilityStatus.RESERVED, AspiraStatus.classifyOccupancy(AspiraStatus.CLOSED))
        assertEquals(AvailabilityStatus.RESERVED, AspiraStatus.classifyOccupancy(AspiraStatus.UNAVAILABLE))
    }
}
