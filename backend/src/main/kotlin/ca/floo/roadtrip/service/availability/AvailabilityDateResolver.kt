package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.availability.ResolvedDateWindow
import ca.floo.roadtrip.service.reservation.CapabilityLimit
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private val GLOBAL_EARLIEST_DATE_CUTOFF: LocalTime = LocalTime.of(18, 0)

internal class AvailabilityDateResolver(
    private val clock: Clock = Clock.systemUTC(),
    private val cutoff: LocalTime = GLOBAL_EARLIEST_DATE_CUTOFF,
) {
    fun context(
        lat: Double?,
        lng: Double?,
    ): PoiDateContext {
        val zone = CoordinateTimeZones.resolve(lat = lat, lng = lng)
        return PoiDateContext(timeZone = zone, earliestDate = earliestDate(zone))
    }

    fun earliestDate(zone: ZoneId): LocalDate {
        val localNow = clock.instant().atZone(zone)
        val localDate = localNow.toLocalDate()
        return if (localNow.toLocalTime().isBefore(cutoff)) localDate else localDate.plusDays(1)
    }

    fun resolveWindow(
        startDate: LocalDate?,
        endDate: LocalDate?,
        context: PoiDateContext,
        bookingHorizon: CapabilityLimit,
        maxDays: Int,
        defaultDays: Int,
    ): ResolvedDateWindow {
        val earliestDate = context.earliestDate
        if (startDate != null && startDate.isBefore(earliestDate)) {
            throw AvailabilityServiceError.BadDateWindow.StartBeforeEarliest(
                earliestDate = earliestDate,
                timeZone = context.timeZone,
            )
        }
        val start = startDate ?: earliestDate
        val end = endDate ?: start.plusDays(defaultDays.toLong())
        if (!end.isAfter(start)) throw AvailabilityServiceError.BadDateWindow.EndBeforeStart
        val latestDate = bookingHorizon.endExclusiveFrom(earliestDate)
        if (end.isAfter(latestDate)) {
            throw AvailabilityServiceError.BadDateWindow.BeyondBookingHorizon(latestDate = latestDate)
        }
        val days = ChronoUnit.DAYS.between(start, end).toInt()
        if (days !in 1..maxDays) throw AvailabilityServiceError.BadDateWindow.WindowTooLong(maxDays = maxDays)
        return ResolvedDateWindow(startDate = start, endDate = end)
    }

    /**
     * The widest window the vendor exposes for a single call, anchored at
     * [anchor] (clamped forward to the earliest bookable date) and capped by
     * [maxPollWindowDays], never running past the booking horizon. Shared by
     * the poller (anchor = earliestDate) and the live read path (anchor = the
     * requested week's start) so the two never drift on how wide a single
     * fetch is. Returns null when the effective span is non-positive or the
     * anchor is already at/after the horizon, so the batcher skips the group
     * and makes no upstream call.
     */
    fun wideWindow(
        anchor: LocalDate,
        context: PoiDateContext,
        maxPollWindowDays: Int,
        bookingHorizon: CapabilityLimit,
    ): ResolvedDateWindow? {
        if (maxPollWindowDays <= 0) return null
        val start = maxOf(context.earliestDate, anchor)
        val horizonEnd = bookingHorizon.endExclusiveFrom(context.earliestDate)
        val end = minOf(horizonEnd, start.plusDays(maxPollWindowDays.toLong()))
        if (!end.isAfter(start)) return null
        return ResolvedDateWindow(startDate = start, endDate = end)
    }

    /**
     * Provider-shaped fetch window for a logical target range. Day-based caps
     * snap to stable epoch-day buckets; month-based caps snap to calendar
     * month buckets. The caller still slices returned observations to [target].
     */
    fun fetchWindow(
        target: ResolvedDateWindow,
        context: PoiDateContext,
        bookingHorizon: CapabilityLimit,
        fetchWindowCap: CapabilityLimit,
    ): ResolvedDateWindow? {
        val horizonEnd = bookingHorizon.endExclusiveFrom(context.earliestDate)
        val targetStart = maxOf(context.earliestDate, target.startDate)
        val targetEnd = minOf(horizonEnd, target.endDate)
        if (!targetEnd.isAfter(targetStart)) return null
        val (bucketStart, bucketEnd) = fetchWindowCap.windowCovering(targetStart, targetEnd) ?: return null
        val fetchStart = maxOf(context.earliestDate, bucketStart)
        val fetchEnd = minOf(horizonEnd, bucketEnd)
        if (!fetchEnd.isAfter(fetchStart)) return null
        return ResolvedDateWindow(startDate = fetchStart, endDate = fetchEnd)
    }

    /**
     * The poller's fetch window: the widest window the vendor exposes for a
     * single tick, anchored at today. Deliberately **independent of any
     * watch's dates** — a watch gates *whether* a poller runs (reference
     * count), never *how wide* it fetches. Since one upstream call returns the
     * whole window's per-day grid at no extra cost, polling maximally widens
     * snapshot history for free.
     *
     * The window is `[earliestDate, min(booking horizon, earliestDate +
     * maxPollWindowDays))`. Because [PoiDateContext.earliestDate] is
     * clock-derived, the window slides forward every day with no state to
     * maintain. Returns null when the effective span is non-positive, such as
     * an unsupported vendor with a zero poll window or horizon, so the batcher
     * skips the group and makes no upstream call.
     */
    fun resolvePollingWindow(
        context: PoiDateContext,
        maxPollWindowDays: Int,
        bookingHorizon: CapabilityLimit,
    ): ResolvedDateWindow? = wideWindow(context.earliestDate, context, maxPollWindowDays, bookingHorizon)
}
