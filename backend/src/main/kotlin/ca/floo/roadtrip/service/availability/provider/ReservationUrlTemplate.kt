package ca.floo.roadtrip.service.availability.provider

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The reservation-URL template vocabulary shared by every provider adapter and
 * the campsites API. A template is a booking URL that may embed the
 * placeholders below; the web app fills them client-side for the user's chosen
 * window ([reservationUrlFromTemplate] in `web/availability/booking-links.js`),
 * and [fill] fills them server-side for a concrete window (e.g. an alert's
 * single night). A template with no placeholders is a static URL and passes
 * through [fill] unchanged — the same contract the web app honors.
 *
 * Keeping the tokens here (not re-declared per adapter) is the single source
 * for the wire contract both ends depend on.
 */
object ReservationUrlTemplate {
    const val START_DATE = "{start_date}"
    const val END_DATE = "{end_date}"
    const val NIGHTS = "{nights}"

    private val placeholders = listOf(START_DATE, END_DATE, NIGHTS)

    /**
     * Concrete booking URL for the half-open window `[startDate, endDate)`.
     * Replaces [START_DATE]/[END_DATE]/[NIGHTS] with the window's dates and
     * night count; a template without placeholders is returned verbatim.
     */
    fun fill(
        template: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): String {
        if (placeholders.none { template.contains(it) }) return template
        val nights = ChronoUnit.DAYS.between(startDate, endDate)
        return template
            .replace(START_DATE, startDate.toString())
            .replace(END_DATE, endDate.toString())
            .replace(NIGHTS, nights.toString())
    }
}
