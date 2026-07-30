package ca.floo.roadtrip.service.notification.email

import ca.floo.roadtrip.service.notification.common.WatchStatusNotice
import kotlinx.html.a
import kotlinx.html.br
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.strong
import kotlinx.html.ul
import java.time.format.DateTimeFormatter
import java.util.Locale

private val statusDateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

internal object EmailContentWatchStatusRenderer {
    fun render(notice: WatchStatusNotice): EmailContent {
        val header = headerFor(notice.state)
        val scope = scopeFor(notice)
        val window = "${notice.startDate.format(statusDateFormatter)}-${notice.endDate.format(statusDateFormatter)}"
        val status = statusLine(notice.state)
        val links = linksFor(notice)
        return EmailContent(
            subject = "Roadtrip watch #${notice.watchId}: $header",
            text = renderText(notice.watchId, header, scope, window, status, links),
            html = renderHtml(notice.watchId, header, scope, window, status, links),
        )
    }

    private fun renderText(
        watchId: Long,
        header: String,
        scope: String,
        window: String,
        status: String,
        links: List<Link>,
    ): String =
        buildString {
            appendLine("$header for watch #$watchId")
            appendLine("Scope: $scope")
            appendLine("Window: $window")
            appendLine("Status: $status")
            links.takeIf { it.isNotEmpty() }?.let {
                appendLine()
                it.forEach { link -> appendLine("${link.label}: ${link.url}") }
            }
        }.trimEnd()

    private fun renderHtml(
        watchId: Long,
        header: String,
        scope: String,
        window: String,
        status: String,
        links: List<Link>,
    ): String =
        createHTML().div {
            h2 { +"$header for watch #$watchId" }
            p {
                strong { +"Scope:" }
                +" $scope"
                br()
                strong { +"Window:" }
                +" $window"
            }
            p { +status }
            links.takeIf { it.isNotEmpty() }?.let {
                ul {
                    it.forEach { link ->
                        li {
                            a(href = link.url) { +link.label }
                        }
                    }
                }
            }
        }

    private fun headerFor(state: WatchStatusNotice.State): String =
        when (state) {
            WatchStatusNotice.State.WATCHING -> "Watching for openings"
            WatchStatusNotice.State.UNCHECKED -> "Watch set"
            WatchStatusNotice.State.PAUSED -> "Watch paused"
            WatchStatusNotice.State.DONE -> "Watch complete"
            WatchStatusNotice.State.STOPPED -> "Watch stopped"
        }

    private fun statusLine(state: WatchStatusNotice.State): String =
        when (state) {
            WatchStatusNotice.State.WATCHING -> "Nothing open right now. We'll email you when a site frees up."
            WatchStatusNotice.State.UNCHECKED -> "Availability has not been checked yet. We'll email you when a site opens."
            WatchStatusNotice.State.PAUSED -> "Paused. No alerts will send until this watch is resumed."
            WatchStatusNotice.State.DONE -> "This watch is complete. No more alerts will send."
            WatchStatusNotice.State.STOPPED -> "This watch was deleted. No more alerts will send."
        }

    private fun scopeFor(notice: WatchStatusNotice): String =
        when {
            notice.siteName != null -> "${notice.siteName}${notice.siteLoop?.let { " ($it)" }.orEmpty()}"
            notice.campgroundName != null -> notice.campgroundName
            else -> "${notice.siteCount} ${"site".plural(notice.siteCount)}"
        }

    private fun linksFor(notice: WatchStatusNotice): List<Link> =
        buildList {
            notice.appRootUrl?.let { root ->
                if (notice.state == WatchStatusNotice.State.PAUSED) {
                    add(Link("Resume watch", "$root/watches?action=modify&id=${notice.watchId}"))
                }
                if (notice.state != WatchStatusNotice.State.DONE && notice.state != WatchStatusNotice.State.STOPPED) {
                    add(Link("Modify watch", "$root/watches?action=modify&id=${notice.watchId}"))
                }
            }
            notice.poiLinks.forEach { poi ->
                poi.mapUrl?.let { add(Link("Map ${poi.poiId}", it)) }
                poi.gridUrl?.let { add(Link("Availability grid ${poi.poiId}", it)) }
            }
        }

    private fun String.plural(count: Int): String = if (count == 1) this else "${this}s"

    private data class Link(
        val label: String,
        val url: String,
    )
}
