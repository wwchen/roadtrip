package ca.floo.roadtrip.service.notification

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
}
