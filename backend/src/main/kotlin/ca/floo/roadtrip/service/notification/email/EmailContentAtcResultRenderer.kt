package ca.floo.roadtrip.service.notification.email

import ca.floo.roadtrip.service.notification.common.AtcResultNotice
import ca.floo.roadtrip.service.notification.common.NeutralBookingCopy
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

private const val FIELD_DETAIL = "detail"

/**
 * The ATC outcome email.
 *
 * Email is the channel that makes ATC results reach their owner at all: the
 * Slack card is fail-closed on a personal token most users never configure, so
 * without this a hold — or a missed one — was announced to nobody.
 *
 * A failure carries the companion's own reason when it gave one, because the
 * two the owner can act on ("session expired — re-login in Settings", a
 * captcha) are exactly the ones a generic message would hide. When it gave
 * none, the adapter's category sentence stands in — never a bare code.
 */
internal object EmailContentAtcResultRenderer {
    private const val FAILED_BODY_PREFIX = "Roadtrip found a matching site but could not hold it:"

    /** Tail for a failure neither we nor the companion put into words. */
    private const val UNEXPLAINED_FAILURE_REASON = "no reason was reported"
    private const val CART_LINK_LABEL = "Open your cart"

    fun render(
        notice: AtcResultNotice,
        magicLinkUrl: String?,
        appRootUrl: String? = null,
    ): EmailContent {
        val completed = notice.status == ATC_STATUS_COMPLETED
        val header = if (completed) heldHeader(notice.bookingSystem) else "Could not hold the site"
        val body =
            if (completed) {
                completedBody(notice.bookingSystem)
            } else {
                failureBody(notice.response, notice.detail)
            }
        val links =
            buildList {
                if (completed) notice.cartUrl?.let { add(EmailLink(cartLinkLabel(notice.bookingSystem), it)) }
                addAll(watchControlLinks(appRootUrl, notice.watchId, magicLinkUrl))
            }
        val providerLabel = notice.providerLabel
        return EmailContent(
            subject = "Roadtrip watch #${notice.watchId}: $header",
            text = renderText(notice.watchId, providerLabel, header, body, links),
            html = renderHtml(notice.watchId, providerLabel, header, body, links),
        )
    }

    /** Mirrors the web toast's title exactly: named when [bookingSystem] is known, neutral otherwise. */
    private fun heldHeader(bookingSystem: String?): String {
        val whoseCart = bookingSystem?.let { "$it " }.orEmpty()
        return "Site held in your ${whoseCart}cart"
    }

    /**
     * The hold's own vendor, named twice: whose cart the site is in, and where
     * the booking has to be finished. Both come from the adapter that made the
     * hold, so this path names no vendor of its own.
     */
    private fun completedBody(bookingSystem: String?): String {
        val whoseCart = bookingSystem?.let { " $it" }.orEmpty()
        val finishOn = bookingSystem ?: NeutralBookingCopy.BOOKING_SITE
        return "A matching site is held in your$whoseCart cart. Holds expire, so finish the booking on " +
            "$finishOn soon. Roadtrip stops at the cart — it never pays."
    }

    /** Aligned with the web toast's cart link: named when [bookingSystem] is known, neutral otherwise. */
    private fun cartLinkLabel(bookingSystem: String?): String = bookingSystem?.let { "Open $it cart" } ?: CART_LINK_LABEL

    private fun renderText(
        watchId: Long,
        providerLabel: String,
        header: String,
        body: String,
        links: List<EmailLink>,
    ): String =
        buildString {
            appendLine("$header for watch #$watchId")
            appendLine("Provider: $providerLabel")
            appendLine()
            appendLine(body)
            links.takeIf { it.isNotEmpty() }?.let {
                appendLine()
                it.forEach { link -> appendLine("${link.label}: ${link.url}") }
            }
        }.trimEnd()

    private fun renderHtml(
        watchId: Long,
        providerLabel: String,
        header: String,
        body: String,
        links: List<EmailLink>,
    ): String =
        createHTML().div {
            h2 { +"$header for watch #$watchId" }
            p {
                strong { +"Provider:" }
                +" $providerLabel"
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
     * Sentences only, ours before the companion's: a code is not an explanation,
     * and `cart_not_added` in an inbox tells the owner nothing. The code and the
     * category travel in the notice's structured fields instead.
     */
    private fun failureBody(
        response: JsonObject?,
        detail: String?,
    ): String {
        val reason = detail ?: response?.textField(FIELD_DETAIL) ?: UNEXPLAINED_FAILURE_REASON
        return "$FAILED_BODY_PREFIX $reason"
    }

    /** Untyped by design: the companion answers with whatever it knows. */
    private fun JsonObject.textField(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
}
