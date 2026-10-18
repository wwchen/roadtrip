package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AvailabilityRunLengthsTest {
    @Test
    fun `empty window has no run`() {
        assertEquals(0, longestRunNights(emptyList()))
    }

    @Test
    fun `all available is one full run`() {
        assertEquals(5, longestRunNights(List(5) { AvailabilityStatus.AVAILABLE }))
    }

    @Test
    fun `all reserved has no run`() {
        assertEquals(0, longestRunNights(List(5) { AvailabilityStatus.RESERVED }))
    }

    @Test
    fun `run at the leading boundary counts`() {
        val days =
            listOf(
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.RESERVED,
            )
        assertEquals(2, longestRunNights(days))
    }

    @Test
    fun `run at the trailing boundary counts`() {
        val days =
            listOf(
                AvailabilityStatus.RESERVED,
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.AVAILABLE,
            )
        assertEquals(2, longestRunNights(days))
    }

    @Test
    fun `longest of several runs wins`() {
        val days =
            listOf(
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.RESERVED,
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.RESERVED,
                AvailabilityStatus.AVAILABLE,
            )
        assertEquals(3, longestRunNights(days))
    }

    @Test
    fun `first come does not count toward a run`() {
        val days =
            listOf(
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.FIRST_COME,
                AvailabilityStatus.AVAILABLE,
            )
        assertEquals(1, longestRunNights(days))
    }

    @Test
    fun `an unanswered night breaks a run`() {
        val days =
            listOf(
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.UNKNOWN,
                AvailabilityStatus.AVAILABLE,
            )
        assertEquals(1, longestRunNights(days))
    }
}
