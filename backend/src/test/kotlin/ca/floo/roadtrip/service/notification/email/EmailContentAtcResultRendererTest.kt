package ca.floo.roadtrip.service.notification.email

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmailContentAtcResultRendererTest {
    @Test
    fun `a completed hold reads as good news and links the recipient back in`() {
        val content =
            EmailContentAtcResultRenderer.render(
                watchId = 42L,
                vendor = "recgov",
                status = "completed",
                response = buildJsonObject { put("cart_added", true) },
                magicLinkUrl = "https://roadtrip.example/watches?token=abc",
            )

        assertTrue(content.subject.contains("#42"), content.subject)
        assertTrue(content.text.contains("cart"), content.text)
        assertTrue(content.html.contains("https://roadtrip.example/watches?token=abc"), content.html)
    }

    @Test
    fun `a failure carries the companion's reason so the owner knows what to do`() {
        val content =
            EmailContentAtcResultRenderer.render(
                watchId = 7L,
                vendor = "recgov",
                status = "failed",
                response =
                    buildJsonObject {
                        put("error", "recgov_session_expired")
                        put("detail", "session expired — re-login in Settings")
                    },
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
                watchId = 7L,
                vendor = "recgov",
                status = "failed",
                response = null,
                error = "recgov_session_expired",
                detail = "session expired — re-login in Settings",
                magicLinkUrl = null,
            )

        assertTrue(content.text.contains("session expired — re-login in Settings"), content.text)
    }

    @Test
    fun `the caller's reason wins over anything in the companion response`() {
        val content =
            EmailContentAtcResultRenderer.render(
                watchId = 7L,
                vendor = "recgov",
                status = "failed",
                response = buildJsonObject { put("detail", "stale companion text") },
                detail = "session expired — re-login in Settings",
                magicLinkUrl = null,
            )

        assertTrue(content.text.contains("session expired"), content.text)
        assertFalse(content.text.contains("stale companion text"), content.text)
    }

    @Test
    fun `dynamic fields are escaped in the html body`() {
        val content =
            EmailContentAtcResultRenderer.render(
                watchId = 7L,
                vendor = "recgov",
                status = "failed",
                response = buildJsonObject { put("detail", "broke on <b>site</b> & \"A\"") },
                magicLinkUrl = null,
            )

        assertTrue(content.html.contains("&lt;b&gt;site&lt;/b&gt; &amp; &quot;A&quot;"), content.html)
        assertFalse(content.html.contains("<b>site</b>"), content.html)
    }
}
