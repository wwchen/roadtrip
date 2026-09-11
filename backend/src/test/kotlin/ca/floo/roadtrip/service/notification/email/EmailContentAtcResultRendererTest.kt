package ca.floo.roadtrip.service.notification.email

import ca.floo.roadtrip.service.notification.common.AtcResultNotice
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A machine identifier as this codebase spells one: `cart_not_added`, `recgov_session_expired`. */
private val codeShaped = Regex("""\b[a-z0-9]+(?:_[a-z0-9]+)+\b""")

class EmailContentAtcResultRendererTest {
    private fun notice(
        watchId: Long = 7L,
        vendor: String? = "recgov",
        status: String = "failed",
        response: JsonObject? = null,
        bookingSystem: String? = null,
        cartUrl: String? = null,
        error: String? = null,
        detail: String? = null,
    ) = AtcResultNotice(
        watchId = watchId,
        vendor = vendor,
        status = status,
        request = JsonObject(emptyMap()),
        response = response,
        bookingSystem = bookingSystem,
        cartUrl = cartUrl,
        error = error,
        detail = detail,
    )

    @Test
    fun `a completed hold reads as good news and links the recipient back in`() {
        val content =
            EmailContentAtcResultRenderer.render(
                notice(
                    watchId = 42L,
                    status = "completed",
                    response = buildJsonObject { put("cart_added", true) },
                ),
                magicLinkUrl = "https://roadtrip.example/watches?token=abc",
            )

        assertTrue(content.subject.contains("#42"), content.subject)
        assertTrue(content.text.contains("cart"), content.text)
        assertTrue(content.html.contains("https://roadtrip.example/watches?token=abc"), content.html)
    }

    @Test
    fun `a hold names the provider holding it and links that provider's cart`() {
        // The provider that made the hold is the only one that knows whose cart
        // the site is in; the copy takes both from the outcome and names no
        // vendor of its own.
        val content =
            EmailContentAtcResultRenderer.render(
                notice(
                    watchId = 42L,
                    vendor = "campflare",
                    status = "completed",
                    response = buildJsonObject { put("cart_added", true) },
                    bookingSystem = "Campflare",
                    cartUrl = "https://cart.example.test/hold",
                ),
                magicLinkUrl = null,
            )

        assertTrue(content.text.contains("held in your Campflare cart"), content.text)
        assertTrue(content.text.contains("finish the booking on Campflare"), content.text)
        assertTrue(content.text.contains("https://cart.example.test/hold"), content.text)
        assertTrue(content.html.contains("https://cart.example.test/hold"), content.html)
        assertFalse(content.text.lowercase().contains("recreation.gov"), content.text)
        assertFalse(content.html.lowercase().contains("recreation.gov"), content.html)
    }

    @Test
    fun `a hold that names no provider still reads as a hold`() {
        val content =
            EmailContentAtcResultRenderer.render(
                notice(status = "completed", response = buildJsonObject { put("cart_added", true) }),
                magicLinkUrl = null,
            )

        assertTrue(content.text.contains("held in your cart"), content.text)
        assertFalse(content.text.lowercase().contains("recreation.gov"), content.text)
    }

    @Test
    fun `a fire that reached no adapter and no opening says the booking site, never unknown`() {
        val content =
            EmailContentAtcResultRenderer.render(
                notice(vendor = null, error = "unsupported_target"),
                magicLinkUrl = null,
            )

        assertTrue(content.text.contains("Provider: the booking site"), content.text)
        assertTrue(content.html.contains("the booking site"), content.html)
        assertFalse(content.text.lowercase().contains("unknown"), content.text)
    }

    @Test
    fun `a failed notice names the provider that would have held it but links no cart`() {
        // The adapter can fail after it already knows whose cart it was trying
        // for; the failure copy still names that provider, but there is no hold
        // to link back to.
        val content =
            EmailContentAtcResultRenderer.render(
                notice(
                    vendor = "campflare",
                    status = "failed",
                    bookingSystem = "Campflare",
                    cartUrl = "https://cart.example.test/hold",
                    error = "captcha_required",
                ),
                magicLinkUrl = null,
            )

        assertTrue(content.text.contains("Provider: Campflare"), content.text)
        assertTrue(content.html.contains("Campflare"), content.html)
        assertFalse(content.text.contains("cart.example.test"), content.text)
        assertFalse(content.html.contains("cart.example.test"), content.html)
        assertFalse(content.text.contains("Open Campflare cart"), content.text)
    }

    @Test
    fun `a bare vendor slug is never rendered as a provider name`() {
        // `vendor` is the opening's raw slug, structured data rather than copy.
        // Only an adapter's own display name is fit to print.
        val content =
            EmailContentAtcResultRenderer.render(
                notice(vendor = "recgov", bookingSystem = null, error = "cart_not_added"),
                magicLinkUrl = null,
            )

        assertTrue(content.text.contains("Provider: the booking site"), content.text)
        assertFalse(content.text.contains("recgov"), content.text)
        assertFalse(content.html.contains("recgov"), content.html)
    }

    @Test
    fun `a failure nobody explained reads as a sentence, never as a raw code`() {
        val content =
            EmailContentAtcResultRenderer.render(
                notice(error = "cart_not_added", response = buildJsonObject { put("error", "recgov_no_reserve_button") }),
                magicLinkUrl = null,
            )

        assertFalse(content.text.contains("cart_not_added"), content.text)
        assertFalse(content.text.contains("recgov_no_reserve_button"), content.text)
        assertFalse(content.html.contains("cart_not_added"), content.html)
        assertTrue(content.text.contains("could not hold it"), content.text)
    }

    @Test
    fun `a cart_not_added failure with no detail renders a sentence, never a code`() {
        // The code travels in the notice's `error` field, which this email never
        // prints: with nothing to explain the failure, the copy still reads as
        // prose rather than falling back to the identifier.
        val content =
            EmailContentAtcResultRenderer.render(
                notice(error = "cart_not_added", detail = null),
                magicLinkUrl = null,
            )

        assertFalse(content.text.contains("cart_not_added"), content.text)
        assertFalse(content.html.contains("cart_not_added"), content.html)
        assertFalse(codeShaped.containsMatchIn(content.text), content.text)
        assertFalse(codeShaped.containsMatchIn(content.html), content.html)
        assertTrue(content.text.contains("could not hold it"), content.text)
    }

    @Test
    fun `a failure carries the companion's reason so the owner knows what to do`() {
        val content =
            EmailContentAtcResultRenderer.render(
                notice(
                    response =
                        buildJsonObject {
                            put("error", "recgov_session_expired")
                            put("detail", "session expired — re-login in Settings")
                        },
                ),
                magicLinkUrl = null,
            )

        assertTrue(content.text.contains("session expired — re-login in Settings"), content.text)
        assertTrue(content.html.contains("session expired"), content.html)
    }

    @Test
    fun `a preflight failure with no companion response still names its reason`() {
        // The producer's own shape: RecGovBookingAdapter fails before the
        // companion is called, so there is nothing in `response` to read.
        val content =
            EmailContentAtcResultRenderer.render(
                notice(
                    error = "recgov_session_expired",
                    detail = "session expired — re-login in Settings",
                ),
                magicLinkUrl = null,
            )

        assertTrue(content.text.contains("session expired — re-login in Settings"), content.text)
    }

    @Test
    fun `the caller's reason wins over anything in the companion response`() {
        val content =
            EmailContentAtcResultRenderer.render(
                notice(
                    response = buildJsonObject { put("detail", "stale companion text") },
                    detail = "session expired — re-login in Settings",
                ),
                magicLinkUrl = null,
            )

        assertTrue(content.text.contains("session expired"), content.text)
        assertFalse(content.text.contains("stale companion text"), content.text)
    }

    @Test
    fun `dynamic fields are escaped in the html body`() {
        val content =
            EmailContentAtcResultRenderer.render(
                notice(response = buildJsonObject { put("detail", "broke on <b>site</b> & \"A\"") }),
                magicLinkUrl = null,
            )

        assertTrue(content.html.contains("&lt;b&gt;site&lt;/b&gt; &amp; &quot;A&quot;"), content.html)
        assertFalse(content.html.contains("<b>site</b>"), content.html)
    }
}
