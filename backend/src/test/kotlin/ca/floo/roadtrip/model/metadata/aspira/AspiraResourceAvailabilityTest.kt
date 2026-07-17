package ca.floo.roadtrip.model.metadata.aspira

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AspiraResourceAvailabilityTest {
    @Test
    fun `available resource code maps to available`() {
        assertEquals(AvailabilityStatus.AVAILABLE, AspiraResourceAvailability.classify(AspiraResourceAvailability.AVAILABLE))
    }

    @Test
    fun `nonzero resource codes map to reserved`() {
        assertEquals(AvailabilityStatus.RESERVED, AspiraResourceAvailability.classify(1))
        assertEquals(AvailabilityStatus.RESERVED, AspiraResourceAvailability.classify(4))
    }

    @Test
    fun `unknown sentinel maps to unknown`() {
        assertEquals(AvailabilityStatus.UNKNOWN, AspiraResourceAvailability.classify(AspiraResourceAvailability.UNKNOWN))
    }
}
