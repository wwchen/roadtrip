package ca.floo.roadtrip.service.notification.email

import ca.floo.roadtrip.service.notification.common.WatchOpening
import kotlinx.html.a
import kotlinx.html.br
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.li
import kotlinx.html.ol
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.strong
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MAX_SUBJECT_CAMPGROUND_CHARS = 80
private const val ELLIPSIS = "..."
private val DATE_FORMATTER = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

internal object EmailContentAvailabilityRenderer {
    fun openings(
        watchId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
        openings: List<WatchOpening>,
        appRootUrl: String?,
    ): EmailContent {
        val countLabel = "${openings.size} ${"site".plural(openings.size)}"
        val campground = openings.mapNotNull { it.campground }.distinct().singleOrNull()
        val subject =
            buildString {
                append("Roadtrip alert: ")
                append(countLabel)
                append(" opened")
                campground?.let {
                    append(" at ")
                    append(it.truncateSubjectPart())
                }
            }
        val watchUrl = appRootUrl?.let { "${it.trimEnd('/')}/availability?watch=$watchId" }
        val window = "${startDate.format(DATE_FORMATTER)}-${endDate.format(DATE_FORMATTER)}"
        val text =
            buildString {
                appendLine("Sites available for watch #$watchId")
                appendLine("Window: $window")
                watchUrl?.let { appendLine("Watch: $it") }
                appendLine()
                openings.forEachIndexed { index, opening ->
                    appendLine("${index + 1}. ${opening.label}")
                    opening.campground?.let { appendLine("   Campground: $it") }
                    appendLine("   Date: ${opening.date.format(DATE_FORMATTER)}")
                    opening.loop?.let { appendLine("   Loop: $it") }
                    opening.siteType?.let { appendLine("   Type: $it") }
                    opening.bookingUrl?.let { appendLine("   Book: $it") }
                    appendLine()
                }
            }.trimEnd()
        val html = renderHtml(watchId = watchId, window = window, watchUrl = watchUrl, openings = openings)
        return EmailContent(subject = subject, text = text, html = html)
    }

    private fun renderHtml(
        watchId: Long,
        window: String,
        watchUrl: String?,
        openings: List<WatchOpening>,
    ): String =
        createHTML().div {
            h2 { +"Sites available for watch #$watchId" }
            p {
                strong { +"Window:" }
                +" $window"
                watchUrl?.let {
                    br()
                    a(href = it) { +"Open watch" }
                }
            }
            ol {
                openings.forEach { opening ->
                    li {
                        strong { +opening.label }
                        br()
                        opening.campground?.let {
                            +"Campground: $it"
                            br()
                        }
                        +"Date: ${opening.date.format(DATE_FORMATTER)}"
                        opening.loop?.let {
                            br()
                            +"Loop: $it"
                        }
                        opening.siteType?.let {
                            br()
                            +"Type: $it"
                        }
                        opening.bookingUrl?.let {
                            br()
                            a(href = it) { +"Book site" }
                        }
                    }
                }
            }
        }

    private fun String.plural(count: Int): String = if (count == 1) this else "${this}s"

    private fun String.truncateSubjectPart(): String =
        if (length <= MAX_SUBJECT_CAMPGROUND_CHARS) this else take(MAX_SUBJECT_CAMPGROUND_CHARS - ELLIPSIS.length) + ELLIPSIS
}
