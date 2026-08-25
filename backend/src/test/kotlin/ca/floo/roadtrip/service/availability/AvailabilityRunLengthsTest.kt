package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals

private val observedAt: Instant = Instant.parse("2026-08-23T12:00:00Z")
private val day0: LocalDate = LocalDate.of(2026, 9, 4)

private fun observations(vararg statuses: AvailabilityStatus): List<CampsiteDayObservation> =
    statuses.mapIndexed { index, status ->
        CampsiteDayObservation(
            campsiteId = 1L,
            date = day0.plusDays(index.toLong()),
            observedAt = observedAt,
            status = status,
        )
    }

class AvailabilityRunLengthsTest {
    @Test
    fun `empty window has no run`() {
        assertEquals(0, longestRunNights(emptyList()))
    }

    @Test
    fun `all available is one full run`() {
        val days = observations(*Array(5) { AvailabilityStatus.AVAILABLE })
        assertEquals(5, longestRunNights(days))
    }

    @Test
    fun `all reserved has no run`() {
        val days = observations(*Array(5) { AvailabilityStatus.RESERVED })
        assertEquals(0, longestRunNights(days))
    }

    @Test
    fun `run at the leading boundary counts`() {
        val days =
            observations(
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.RESERVED,
            )
        assertEquals(2, longestRunNights(days))
    }

    @Test
    fun `run at the trailing boundary counts`() {
        val days =
            observations(
                AvailabilityStatus.RESERVED,
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.AVAILABLE,
            )
        assertEquals(2, longestRunNights(days))
    }

    @Test
    fun `longest of several runs wins`() {
        val days =
            observations(
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
            observations(
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.FIRST_COME,
                AvailabilityStatus.AVAILABLE,
            )
        assertEquals(1, longestRunNights(days))
    }

    @Test
    fun `a missing date breaks a run`() {
        val days =
            listOf(
                CampsiteDayObservation(1L, day0, observedAt, AvailabilityStatus.AVAILABLE),
                CampsiteDayObservation(1L, day0.plusDays(2), observedAt, AvailabilityStatus.AVAILABLE),
            )
        assertEquals(1, longestRunNights(days))
    }

    @Test
    fun `unordered input is scanned in date order`() {
        val days =
            listOf(
                CampsiteDayObservation(1L, day0.plusDays(2), observedAt, AvailabilityStatus.AVAILABLE),
                CampsiteDayObservation(1L, day0, observedAt, AvailabilityStatus.AVAILABLE),
                CampsiteDayObservation(1L, day0.plusDays(1), observedAt, AvailabilityStatus.AVAILABLE),
            )
        assertEquals(3, longestRunNights(days))
    }
}
