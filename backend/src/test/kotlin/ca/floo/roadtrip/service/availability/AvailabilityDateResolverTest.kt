package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.ResolvedDateWindow
import ca.floo.roadtrip.service.reservation.CapabilityLimit
import ca.floo.roadtrip.service.reservation.CapabilityTimeUnit
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit coverage for the poller's fetch-window policy: it is anchored at today
 * (the target-local earliest bookable date) and capped by the vendor,
 * deliberately independent of any watch's dates.
 */
class AvailabilityDateResolverTest {
    private fun resolverAt(instant: String): AvailabilityDateResolver =
        AvailabilityDateResolver(clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC))

    private fun dayLimit(days: Int): CapabilityLimit = CapabilityLimit(days, CapabilityTimeUnit.DAY)

    @Test
    fun `polling window starts at the earliest bookable date and spans the vendor cap`() {
        val resolver = resolverAt("2026-07-04T00:00:00Z")
        val context = resolver.context(lat = null, lng = null)

        val window = resolver.resolvePollingWindow(context, maxPollWindowDays = 60, bookingHorizon = dayLimit(180))!!

        assertEquals(context.earliestDate, window.startDate)
        assertEquals(60L, ChronoUnit.DAYS.between(window.startDate, window.endDate))
    }

    @Test
    fun `window is clamped to the booking horizon when it is tighter than the poll cap`() {
        val resolver = resolverAt("2026-07-04T00:00:00Z")
        val context = resolver.context(lat = null, lng = null)

        val window = resolver.resolvePollingWindow(context, maxPollWindowDays = 90, bookingHorizon = dayLimit(30))!!

        assertEquals(30L, ChronoUnit.DAYS.between(window.startDate, window.endDate))
    }

    @Test
    fun `window slides forward with the clock`() {
        val early = resolverAt("2026-07-04T00:00:00Z").let { it.resolvePollingWindow(it.context(null, null), 60, dayLimit(180))!! }
        val later = resolverAt("2026-07-11T00:00:00Z").let { it.resolvePollingWindow(it.context(null, null), 60, dayLimit(180))!! }

        assertEquals(7L, ChronoUnit.DAYS.between(early.startDate, later.startDate))
    }

    @Test
    fun `a zero vendor poll window yields no window so the batcher skips the group`() {
        val resolver = resolverAt("2026-07-04T00:00:00Z")
        val context = resolver.context(lat = null, lng = null)

        assertNull(resolver.resolvePollingWindow(context, maxPollWindowDays = 0, bookingHorizon = dayLimit(180)))
        assertNull(resolver.resolvePollingWindow(context, maxPollWindowDays = 60, bookingHorizon = dayLimit(0)))
    }

    @Test
    fun `wideWindow anchored at earliest equals the polling window`() {
        val resolver = resolverAt("2026-07-04T00:00:00Z")
        val context = resolver.context(lat = null, lng = null)

        val wide = resolver.wideWindow(context.earliestDate, context, maxPollWindowDays = 60, bookingHorizon = dayLimit(180))!!
        val polling = resolver.resolvePollingWindow(context, maxPollWindowDays = 60, bookingHorizon = dayLimit(180))!!

        assertEquals(polling.startDate, wide.startDate)
        assertEquals(polling.endDate, wide.endDate)
    }

    @Test
    fun `wideWindow anchors at a future target start and spans the vendor cap`() {
        val resolver = resolverAt("2026-07-04T00:00:00Z")
        val context = resolver.context(lat = null, lng = null)
        val anchor = context.earliestDate.plusDays(90)

        val wide = resolver.wideWindow(anchor, context, maxPollWindowDays = 30, bookingHorizon = dayLimit(365))!!

        assertEquals(anchor, wide.startDate)
        assertEquals(30L, ChronoUnit.DAYS.between(wide.startDate, wide.endDate))
    }

    @Test
    fun `wideWindow clamps a past anchor up to the earliest bookable date`() {
        val resolver = resolverAt("2026-07-04T00:00:00Z")
        val context = resolver.context(lat = null, lng = null)
        val pastAnchor = context.earliestDate.minusDays(10)

        val wide = resolver.wideWindow(pastAnchor, context, maxPollWindowDays = 30, bookingHorizon = dayLimit(365))!!

        assertEquals(context.earliestDate, wide.startDate)
    }

    @Test
    fun `wideWindow end never passes the booking horizon`() {
        val resolver = resolverAt("2026-07-04T00:00:00Z")
        val context = resolver.context(lat = null, lng = null)
        val horizonEnd = context.earliestDate.plusDays(30)
        val anchor = context.earliestDate.plusDays(20)

        val wide = resolver.wideWindow(anchor, context, maxPollWindowDays = 60, bookingHorizon = dayLimit(30))!!

        assertEquals(horizonEnd, wide.endDate)
    }

    @Test
    fun `month booking horizon uses calendar months instead of fixed thirty day months`() {
        val resolver = resolverAt("2026-01-31T00:00:00Z")
        val context = resolver.context(lat = null, lng = null)
        val anchor = context.earliestDate.plusMonths(6).minusDays(10)

        val wide =
            resolver.wideWindow(
                anchor = anchor,
                context = context,
                maxPollWindowDays = 60,
                bookingHorizon = CapabilityLimit(6, CapabilityTimeUnit.MONTH),
            )!!

        assertEquals(context.earliestDate.plusMonths(6), wide.endDate)
    }

    @Test
    fun `day fetch window cap snaps to epoch-day bucket covering the target range`() {
        val resolver = resolverAt("2026-06-01T00:00:00Z")
        val context = resolver.context(lat = null, lng = null)

        val fetch =
            resolver.fetchWindow(
                target =
                    ResolvedDateWindow(
                        startDate = LocalDate.parse("2026-07-11"),
                        endDate = LocalDate.parse("2026-07-12"),
                    ),
                context = context,
                bookingHorizon = dayLimit(365),
                fetchWindowCap = CapabilityLimit(14, CapabilityTimeUnit.DAY),
            )!!

        assertEquals(LocalDate.parse("2026-07-02"), fetch.startDate)
        assertEquals(LocalDate.parse("2026-07-16"), fetch.endDate)
    }

    @Test
    fun `day fetch window cap spans every epoch bucket intersecting the target range`() {
        val resolver = resolverAt("2026-06-01T00:00:00Z")
        val context = resolver.context(lat = null, lng = null)

        val fetch =
            resolver.fetchWindow(
                target =
                    ResolvedDateWindow(
                        startDate = LocalDate.parse("2026-07-11"),
                        endDate = LocalDate.parse("2026-07-18"),
                    ),
                context = context,
                bookingHorizon = dayLimit(365),
                fetchWindowCap = CapabilityLimit(14, CapabilityTimeUnit.DAY),
            )!!

        assertEquals(LocalDate.parse("2026-07-02"), fetch.startDate)
        assertEquals(LocalDate.parse("2026-07-30"), fetch.endDate)
    }

    @Test
    fun `wideWindow yields no window for a zero cap or an anchor at the horizon`() {
        val resolver = resolverAt("2026-07-04T00:00:00Z")
        val context = resolver.context(lat = null, lng = null)

        assertNull(resolver.wideWindow(context.earliestDate, context, maxPollWindowDays = 0, bookingHorizon = dayLimit(180)))
        assertNull(resolver.wideWindow(context.earliestDate.plusDays(180), context, maxPollWindowDays = 60, bookingHorizon = dayLimit(180)))
    }
}
