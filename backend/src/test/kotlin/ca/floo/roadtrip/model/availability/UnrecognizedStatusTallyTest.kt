package ca.floo.roadtrip.model.availability

import kotlin.test.Test
import kotlin.test.assertEquals

class UnrecognizedStatusTallyTest {
    @Test
    fun `a classified value passes through and counts nothing`() {
        val tally = UnrecognizedStatusTally()

        assertEquals(AvailabilityStatus.RESERVED, tally.orUnknown("Reserved", AvailabilityStatus.RESERVED))
        assertEquals(emptyMap(), tally.counts())
    }

    @Test
    fun `counts from several pages merge under the same verbatim value`() {
        val tally = UnrecognizedStatusTally()
        tally.orUnknown("q/Q", null)
        tally.addAll(mapOf("q/Q" to 2, "z/Z" to 1))

        assertEquals(mapOf("q/Q" to 3, "z/Z" to 1), tally.counts())
    }
}
