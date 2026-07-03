package ca.floo.roadtrip.models.availability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AvailabilityStatusTest {
    @Test
    fun `PAST parses from wire value and is not online-bookable`() {
        assertEquals(AvailabilityStatus.PAST, AvailabilityStatus.parse("past"))
        assertFalse(AvailabilityStatus.PAST.isOnlineBookable)
    }
}
