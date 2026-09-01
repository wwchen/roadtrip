package ca.floo.roadtrip.service.notification.email

import kotlinx.html.a
import kotlinx.html.br
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.strong
import kotlinx.html.ul
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The success value `AtcTriggerActionHandler` reports; anything else failed. */
internal const val ATC_STATUS_COMPLETED = "completed"

private const val FIELD_ERROR = "error"
private const val FIELD_DETAIL = "detail"

/**
 * The ATC outcome email.
 *
 * Email is the channel that makes ATC results reach their owner at all: the
 * Slack card is fail-closed on a personal token most users never configure, so
 * without this a hold — or a missed one — was announced to nobody.
 *
 * A failure carries the companion's own reason verbatim, because the two the
 * owner can act on ("session expired — re-login in Settings", a captcha) are
 * exactly the ones a generic message would hide.
 */
internal object EmailContentAtcResultRenderer {
    private const val COMPLETED_BODY =
        "A matching site is held in your recreation.gov cart. Holds expire, so finish the booking on " +
            "recreation.gov soon. Roadtrip stops at the cart — it never pays."
    private const val FAILED_BODY_PREFIX = "Roadtrip found a matching site but could not hold it:"

    fun render(
        watchId: Long,
        vendor: String,
        status: String,
        response: JsonObject?,
        error: String? = null,
        detail: String? = null,
        magicLinkUrl: String?,
        appRootUrl: String? = null,
    ): EmailContent {
        val completed = status == ATC_STATUS_COMPLETED
        val header = if (completed) "Site held in your cart" else "Could not hold the site"
        val body = if (completed) COMPLETED_BODY else failureBody(response, error, detail)
        val links = watchControlLinks(appRootUrl, watchId, magicLinkUrl)
        return EmailContent(
            subject = "Roadtrip watch #$watchId: $header",
            text = renderText(watchId, vendor, header, body, links),
            html = renderHtml(watchId, vendor, header, body, links),
        )
    }

    private fun renderText(
        watchId: Long,
        vendor: String,
        header: String,
        body: String,
        links: List<EmailLink>,
    ): String =
        buildString {
            appendLine("$header for watch #$watchId")
            appendLine("Provider: $vendor")
            appendLine()
            appendLine(body)
            links.takeIf { it.isNotEmpty() }?.let {
                appendLine()
                it.forEach { link -> appendLine("${link.label}: ${link.url}") }
            }
        }.trimEnd()

    private fun renderHtml(
        watchId: Long,
        vendor: String,
        header: String,
        body: String,
        links: List<EmailLink>,
    ): String =
        createHTML().div {
            h2 { +"$header for watch #$watchId" }
            p {
                strong { +"Provider:" }
                +" $vendor"
                br()
            }
            p { +body }
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

    /**
     * The caller's own reason wins over anything in the companion response. A
     * preflight failure — a dead session, an unreachable companion — produces no
     * response at all, and those are exactly the failures the owner can fix.
     */
    private fun failureBody(
        response: JsonObject?,
        error: String?,
        detail: String?,
    ): String {
        val reason =
            detail
                ?: error
                ?: response?.textField(FIELD_DETAIL)
                ?: response?.textField(FIELD_ERROR)
                ?: "the booking service did not confirm a hold"
        return "$FAILED_BODY_PREFIX $reason"
    }

    /** Untyped by design: the companion answers with whatever it knows. */
    private fun JsonObject.textField(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
}
