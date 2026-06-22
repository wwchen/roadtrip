package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.availability.ResolvedDateWindow
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private val DEFAULT_TIME_ZONE: ZoneId = ZoneId.of("America/Vancouver")
private val GLOBAL_EARLIEST_DATE_CUTOFF: LocalTime = LocalTime.of(18, 0)

internal class AvailabilityDateResolver(
    private val clock: Clock = Clock.systemUTC(),
    private val cutoff: LocalTime = GLOBAL_EARLIEST_DATE_CUTOFF,
) {
    fun context(
        country: String?,
        region: String?,
        lng: Double?,
    ): PoiDateContext {
        val zone = resolvePoiTimeZone(country = country, region = region, lng = lng)
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

internal fun resolvePoiTimeZone(
    country: String?,
    region: String?,
    lng: Double?,
): ZoneId {
    val countryCode = country?.trim()?.uppercase()
    val regionCode = region?.trim()?.uppercase()
    val zone =
        when (countryCode) {
            "CA" -> canadianZone(regionCode, lng)
            "US" -> usZone(regionCode, lng)
            else -> longitudeZone(lng)
        }
    return ZoneId.of(zone)
}

private fun canadianZone(
    region: String?,
    lng: Double?,
): String =
    when (region) {
        "BC" -> "America/Vancouver"
        "AB" -> "America/Edmonton"
        "SK" -> "America/Regina"
        "MB" -> "America/Winnipeg"
        "ON", "QC", "NU" -> canadianCentralEasternZone(lng)
        "NB", "NS", "PE" -> "America/Halifax"
        "NL" -> "America/St_Johns"
        "YT" -> "America/Whitehorse"
        "NT" -> "America/Yellowknife"
        else -> longitudeZone(lng)
    }

private fun canadianCentralEasternZone(lng: Double?): String =
    when {
        lng == null -> "America/Toronto"
        lng < -90.0 -> "America/Winnipeg"
        lng < -82.5 -> "America/Toronto"
        lng < -63.0 -> "America/Toronto"
        else -> "America/Halifax"
    }

private fun usZone(
    region: String?,
    lng: Double?,
): String =
    when (region) {
        "AK" -> "America/Anchorage"
        "AL", "AR", "IA", "IL", "LA", "MN", "MO", "MS", "WI" -> "America/Chicago"
        "AZ" -> "America/Phoenix"
        "CA", "NV", "WA" -> "America/Los_Angeles"
        "CO", "MT", "NM", "UT", "WY" -> "America/Denver"
        "CT", "DC", "DE", "GA", "MA", "MD", "ME", "NH", "NJ", "NY", "NC", "OH", "PA", "RI", "SC", "VA",
        "VT", "WV",
        -> "America/New_York"
        "FL", "IN", "KY", "MI", "ND", "NE", "OR", "SD", "TN", "TX" -> usSplitStateZone(region, lng)
        "HI" -> "Pacific/Honolulu"
        "ID" -> if (lng != null && lng < -114.0) "America/Los_Angeles" else "America/Denver"
        "KS", "OK" -> if (lng != null && lng < -101.0) "America/Denver" else "America/Chicago"
        else -> longitudeZone(lng)
    }

private fun usSplitStateZone(
    region: String,
    lng: Double?,
): String =
    when (region) {
        "FL", "IN", "KY", "MI", "TN" -> if (lng != null && lng < -85.0) "America/Chicago" else "America/New_York"
        "ND", "NE", "SD" -> if (lng != null && lng < -101.0) "America/Denver" else "America/Chicago"
        "OR" -> if (lng != null && lng > -117.0) "America/Denver" else "America/Los_Angeles"
        "TX" -> if (lng != null && lng < -104.0) "America/Denver" else "America/Chicago"
        else -> longitudeZone(lng)
    }

private fun longitudeZone(lng: Double?): String {
    if (lng == null) return DEFAULT_TIME_ZONE.id
    return when {
        lng < -141.0 -> "America/Anchorage"
        lng < -112.5 -> "America/Los_Angeles"
        lng < -97.5 -> "America/Denver"
        lng < -82.5 -> "America/Chicago"
        lng < -67.5 -> "America/New_York"
        lng < -52.5 -> "America/Halifax"
        else -> DEFAULT_TIME_ZONE.id
    }
}
