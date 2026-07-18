package ca.floo.roadtrip.client

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateStringFormatter {
    private val logMonthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern(LOG_MONTH_PATTERN, Locale.ENGLISH)
    private val logDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern(LOG_DATE_PATTERN, Locale.ENGLISH)

    fun month(monthStart: LocalDate): String = monthStart.format(logMonthFormatter)

    fun month(monthStart: String): String =
        runCatching { month(LocalDate.parse(monthStart)) }
            .getOrDefault(monthStart)

    fun date(date: LocalDate): String = date.format(logDateFormatter)
}

private const val LOG_MONTH_PATTERN = "yyyy/MMMM"
private const val LOG_DATE_PATTERN = "yyyy/MMMM/dd"
