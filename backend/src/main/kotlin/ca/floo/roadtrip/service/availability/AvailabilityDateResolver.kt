package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.availability.ResolvedDateWindow
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
        bookingHorizonDays: Int,
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
        val latestDate = earliestDate.plusDays(bookingHorizonDays.toLong())
        if (end.isAfter(latestDate)) {
            throw AvailabilityServiceError.BadDateWindow.BeyondBookingHorizon(latestDate = latestDate)
        }
        val days = ChronoUnit.DAYS.between(start, end).toInt()
        if (days !in 1..maxDays) throw AvailabilityServiceError.BadDateWindow.WindowTooLong(maxDays = maxDays)
        return ResolvedDateWindow(startDate = start, endDate = end)
    }

    fun resolvePollingWindow(
        startDate: LocalDate,
        endDate: LocalDate,
        context: PoiDateContext,
        bookingHorizonDays: Int,
        maxDays: Int,
    ): ResolvedDateWindow? {
        val start = maxOf(startDate, context.earliestDate)
        if (!endDate.isAfter(start)) return null
        return resolveWindow(
            startDate = start,
            endDate = endDate,
            context = context,
            bookingHorizonDays = bookingHorizonDays,
            maxDays = maxDays,
            defaultDays = maxDays,
        )
    }
}
