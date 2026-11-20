package ca.floo.roadtrip.model.availability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvailabilityStatusTest {
    @Test
    fun `PAST parses from wire value and is not online-bookable`() {
        assertEquals(AvailabilityStatus.PAST, AvailabilityStatus.parse("past"))
        assertFalse(AvailabilityStatus.PAST.isOnlineBookable)
    }

    @Test
    fun `every stored literal parses back to its status`() {
        for (status in AvailabilityStatus.entries) {
            assertEquals(status, AvailabilityStatus.parse(status.wireValue))
        }
    }

    @Test
    fun `a stored value outside the vocabulary fails loudly instead of reading as unknown`() {
        val thrown = assertFailsWith<IllegalArgumentException> { AvailabilityStatus.parse("gibberish") }

        assertTrue(thrown.message!!.contains("gibberish"), thrown.message)
    }

    @Test
    fun `a value that only matches after case folding is not a stored literal`() {
        assertFailsWith<IllegalArgumentException> { AvailabilityStatus.parse("Available") }
    }

    @Test
    fun `a missing stored value fails loudly`() {
        assertFailsWith<IllegalArgumentException> { AvailabilityStatus.parse(null) }
    }
}
