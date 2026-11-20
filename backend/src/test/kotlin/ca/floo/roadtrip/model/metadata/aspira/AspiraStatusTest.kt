package ca.floo.roadtrip.model.metadata.aspira

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AspiraStatusTest {
    @Test
    fun `zero is the bookable code`() {
        assertEquals(AvailabilityStatus.AVAILABLE, AspiraStatus.classify(AspiraStatus.AVAILABLE))
    }

    @Test
    fun `unavailable for the search is taken`() {
        assertEquals(AvailabilityStatus.RESERVED, AspiraStatus.classify(AspiraStatus.UNAVAILABLE))
    }

    @Test
    fun `not operating maps to closed`() {
        assertEquals(AvailabilityStatus.CLOSED, AspiraStatus.classify(AspiraStatus.CLOSED))
    }

    @Test
    fun `a non-reservable site is first come first served, not reserved`() {
        assertEquals(AvailabilityStatus.FIRST_COME, AspiraStatus.classify(AspiraStatus.NON_RESERVABLE))
    }

    @Test
    fun `a site closed on its own is closed, not reserved`() {
        assertEquals(AvailabilityStatus.CLOSED, AspiraStatus.classify(AspiraStatus.SITE_CLOSED))
    }

    @Test
    fun `a site outside the searched booking category is unknown, not reserved`() {
        assertEquals(AvailabilityStatus.UNKNOWN, AspiraStatus.classify(AspiraStatus.OTHER_BOOKING_CATEGORY))
    }

    @Test
    fun `the wire codes are the ones the captures carry`() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5),
            listOf(
                AspiraStatus.AVAILABLE,
                AspiraStatus.UNAVAILABLE,
                AspiraStatus.CLOSED,
                AspiraStatus.NON_RESERVABLE,
                AspiraStatus.SITE_CLOSED,
                AspiraStatus.OTHER_BOOKING_CATEGORY,
            ),
        )
    }

    @Test
    fun `an unlisted code is unrecognized, not folded into a status`() {
        assertNull(AspiraStatus.classify(99))
        assertNull(AspiraStatus.classify(-1))
        assertNull(AspiraStatus.classifyOccupancy(99))
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

    @Test
    fun `occupancy rows share the rest of the code family`() {
        assertEquals(AvailabilityStatus.FIRST_COME, AspiraStatus.classifyOccupancy(AspiraStatus.NON_RESERVABLE))
        assertEquals(AvailabilityStatus.CLOSED, AspiraStatus.classifyOccupancy(AspiraStatus.SITE_CLOSED))
        assertEquals(AvailabilityStatus.UNKNOWN, AspiraStatus.classifyOccupancy(AspiraStatus.OTHER_BOOKING_CATEGORY))
    }
}
