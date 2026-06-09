package ca.floo.roadtrip.models.aspira

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AspiraStatusTest {
    @Test
    fun `available codes map to available`() {
        assertEquals("available", AspiraStatus.classify(AspiraStatus.AVAILABLE))
        assertEquals("available", AspiraStatus.classify(AspiraStatus.LIMITED))
    }

    @Test
    fun `partial-shaped codes map to partial`() {
        // 3, 6, 7 all mean "some sub-areas have availability, others don't"
        // — the FE renders the same color either way.
        assertEquals("partial", AspiraStatus.classify(AspiraStatus.PARTIAL))
        assertEquals("partial", AspiraStatus.classify(AspiraStatus.MIXED))
        assertEquals("partial", AspiraStatus.classify(AspiraStatus.MOSTLY_BOOKED))
    }

    @Test
    fun `unavailable + no_data map to closed`() {
        assertEquals("closed", AspiraStatus.classify(AspiraStatus.UNAVAILABLE))
        assertEquals("closed", AspiraStatus.classify(AspiraStatus.NO_DATA))
    }

    @Test
    fun `unknown code falls back to partial`() {
        // Aspira's code set isn't documented; we surface unfamiliar codes as
        // 'partial' so they show up but don't mislead. Verify a value we've
        // never observed.
        assertEquals("partial", AspiraStatus.classify(99))
        assertEquals("partial", AspiraStatus.classify(-1))
    }
}
