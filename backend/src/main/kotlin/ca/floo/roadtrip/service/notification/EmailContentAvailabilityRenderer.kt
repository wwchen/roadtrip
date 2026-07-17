package ca.floo.roadtrip.service.notification

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MAX_SUBJECT_CAMPGROUND_CHARS = 80
private const val ELLIPSIS = "..."
private const val HTML_BREAK = "<br>"
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
        val html =
            buildString {
                append("<h2>Sites available for watch #")
                append(watchId)
                append("</h2>")
                append("<p><strong>Window:</strong> ")
                append(window.escapeHtml())
                watchUrl?.let {
                    append(HTML_BREAK)
                    append("""<a href="${it.escapeHtml()}">Open watch</a>""")
                }
                append("</p><ol>")
                openings.forEach { opening ->
                    append("<li><strong>")
                    append(opening.label.escapeHtml())
                    append("</strong>")
                    val details =
                        listOfNotNull(
                            opening.campground?.let { "Campground: $it" },
                            "Date: ${opening.date.format(DATE_FORMATTER)}",
                            opening.loop?.let { "Loop: $it" },
                            opening.siteType?.let { "Type: $it" },
                            opening.bookingUrl?.let { """<a href="${it.escapeHtml()}">Book site</a>""" },
                        )
                    append("<br>")
                    append(details.joinToString(HTML_BREAK) { it.escapeHtmlUnlessAnchor() })
                    append("</li>")
                }
                append("</ol>")
            }
        return EmailContent(subject = subject, text = text, html = html)
    }

    private fun String.plural(count: Int): String = if (count == 1) this else "${this}s"

    private fun String.truncateSubjectPart(): String =
        if (length <= MAX_SUBJECT_CAMPGROUND_CHARS) this else take(MAX_SUBJECT_CAMPGROUND_CHARS - ELLIPSIS.length) + ELLIPSIS

    private fun String.escapeHtml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun String.escapeHtmlUnlessAnchor(): String = if (startsWith("<a href=")) this else escapeHtml()
}
