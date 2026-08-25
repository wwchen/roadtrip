package ca.floo.roadtrip.service.notification.email

import ca.floo.roadtrip.service.notification.common.WatchOpening
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmailContentAvailabilityRendererTest {
    @Test
    fun `html output escapes dynamic opening fields`() {
        val content =
            EmailContentAvailabilityRenderer.openings(
                watchId = 42L,
                startDate = LocalDate.of(2026, 8, 1),
                endDate = LocalDate.of(2026, 8, 3),
                openings =
                    listOf(
                        WatchOpening(
                            label = "Site <100> & \"A\"",
                            loop = "Loop <A>",
                            siteType = "Tent & RV",
                            date = LocalDate.of(2026, 8, 1),
                            campgroundId = 7L,
                            campground = "Kirk <Creek>",
                            bookingUrl = "https://example.test/book?site=<100>&ref=\"x\"",
                            vendor = "recgov",
                        ),
                    ),
                appRootUrl = "https://roadtrip.example",
            )

        assertTrue(content.html.contains("Site &lt;100&gt; &amp; &quot;A&quot;"), content.html)
        assertTrue(content.html.contains("Kirk &lt;Creek&gt;"), content.html)
        assertTrue(content.html.contains("Loop &lt;A&gt;"), content.html)
        assertTrue(content.html.contains("Tent &amp; RV"), content.html)
        assertTrue(content.html.contains("site=&lt;100&gt;&amp;ref=&quot;x&quot;"), content.html)
        assertFalse(content.html.contains("Site <100>"), content.html)
        assertFalse(content.html.contains("Kirk <Creek>"), content.html)
    }

    @Test
    fun `the manage link is the magic link when one was minted`() {
        val content = openingsWith(manageUrl = "https://roadtrip.example/watches?action=modify&id=42&watch_token=abc")

        assertTrue(content.text.contains("watch_token=abc"), content.text)
        assertTrue(content.html.contains("watch_token=abc"), content.html)
        assertTrue(content.html.contains("Manage or stop this alert"), content.html)
    }

    @Test
    fun `the manage link falls back to the sign-in-gated form with no token`() {
        val content = openingsWith(manageUrl = null)

        // Still a link, still labelled the same — it just asks for a sign-in.
        assertTrue(content.text.contains("https://roadtrip.example/watches?action=modify&id=42"), content.text)
        assertFalse(content.text.contains("watch_token"), content.text)
    }

    private fun openingsWith(manageUrl: String?) =
        EmailContentAvailabilityRenderer.openings(
            watchId = 42L,
            startDate = LocalDate.of(2026, 8, 1),
            endDate = LocalDate.of(2026, 8, 3),
            openings =
                listOf(
                    WatchOpening(
                        label = "Site 100",
                        loop = null,
                        siteType = null,
                        date = LocalDate.of(2026, 8, 1),
                        campgroundId = 7L,
                        campground = "Kirk Creek",
                        bookingUrl = null,
                        vendor = "recgov",
                    ),
                ),
            appRootUrl = "https://roadtrip.example",
            manageUrl = manageUrl,
        )
}
