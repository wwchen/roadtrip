package ca.floo.roadtrip.service.availability

import java.time.LocalDate

internal class AvailabilityWatchDateWindow(
    val startDate: LocalDate,
    val endDate: LocalDate,
) {
    fun dates(): List<LocalDate> = datesIn(startDate, endDate)

    companion object {
        fun parse(
            startDate: String,
            endDate: String,
        ): AvailabilityWatchDateWindow? =
            runCatching {
                val start = LocalDate.parse(startDate)
                val end = LocalDate.parse(endDate)
                if (!end.isAfter(start)) return null
                AvailabilityWatchDateWindow(start, end)
            }.getOrNull()

        fun datesIn(
            startDate: LocalDate,
            endDate: LocalDate,
        ): List<LocalDate> {
            if (!endDate.isAfter(startDate)) return emptyList()
            return generateSequence(startDate) { date ->
                date.plusDays(1).takeIf { it.isBefore(endDate) }
            }.toList()
        }
    }
}
