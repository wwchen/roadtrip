package ca.floo.roadtrip.service.notification.email

import ca.floo.roadtrip.service.notification.common.WatchOpening
import ca.floo.roadtrip.service.notification.common.WatchStatusNotice
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val APP_ROOT = "https://roadtrip.example"
private const val MAGIC_LINK_URL = "$APP_ROOT/watches?watch=42&t=tok-abc"
private const val SESSION_MODIFY_URL = "$APP_ROOT/watches?action=modify&id=42"

/**
 * The same URLs as they must appear inside an `href`. Asserted in escaped form
 * because an unescaped `&` between query parameters is a real bug that still
 * renders fine in whichever client you happen to test in.
 */
private const val MAGIC_LINK_URL_IN_HTML = "$APP_ROOT/watches?watch=42&amp;t=tok-abc"
private const val SESSION_MODIFY_URL_IN_HTML = "$APP_ROOT/watches?action=modify&amp;id=42"

/**
 * What an alert email offers with a magic link, and what it falls back to
 * without one — where the only control is a page that demands a sign-in, so the
 * copy must not promise more.
 */
class EmailMagicLinkTest {
    private fun opening() =
        WatchOpening(
            label = "Site 100",
            loop = "Loop A",
            siteType = "Tent",
            date = LocalDate.of(2026, 8, 1),
            campgroundId = 7L,
            campground = "Kirk Creek",
            bookingUrl = "https://example.test/book",
            vendor = "recgov",
        )

    private fun openings(magicLinkUrl: String?) =
        EmailContentAvailabilityRenderer.openings(
            watchId = 42L,
            startDate = LocalDate.of(2026, 8, 1),
            endDate = LocalDate.of(2026, 8, 3),
            openings = listOf(opening()),
            appRootUrl = APP_ROOT,
            magicLinkUrl = magicLinkUrl,
        )

    private fun status(
        state: WatchStatusNotice.State,
        magicLinkUrl: String?,
    ) = EmailContentWatchStatusRenderer.render(
        WatchStatusNotice(
            watchId = 42L,
            state = state,
            siteCount = 1,
            siteName = "Site 100",
            siteLoop = "Loop A",
            campgroundName = null,
            startDate = LocalDate.of(2026, 8, 1),
            endDate = LocalDate.of(2026, 8, 3),
            poiLinks = emptyList(),
            appRootUrl = APP_ROOT,
        ),
        magicLinkUrl = magicLinkUrl,
    )

    @Test
    fun `an openings alert carries a Manage watch and a Stop watch link`() {
        val content = openings(MAGIC_LINK_URL)

        assertTrue(content.html.contains(MAGIC_LINK_URL_IN_HTML), content.html)
        assertTrue(content.text.contains(MAGIC_LINK_URL), content.text)
        assertTrue(content.html.contains("Manage watch"), content.html)
        assertTrue(content.html.contains("Stop watch"), content.html)
        assertTrue(content.html.contains("$MAGIC_LINK_URL_IN_HTML&amp;action=stop"), content.html)
        // Replaced, not accompanied.
        assertFalse(content.html.contains(SESSION_MODIFY_URL_IN_HTML), content.html)
    }

    @Test
    fun `an openings alert with no token offers Manage but not Stop`() {
        val content = openings(null)

        assertTrue(content.html.contains(SESSION_MODIFY_URL_IN_HTML), content.html)
        assertTrue(content.html.contains("Manage watch"), content.html)
        // Without a token the page could not carry out a stop, so do not offer one.
        assertFalse(content.html.contains("Stop watch"), content.html)
        assertFalse(content.html.contains("action=stop"), content.html)
    }

    @Test
    fun `a live watch's status email carries both controls`() {
        val content = status(WatchStatusNotice.State.WATCHING, MAGIC_LINK_URL)

        assertTrue(content.html.contains(MAGIC_LINK_URL_IN_HTML), content.html)
        assertTrue(content.html.contains("Manage watch"), content.html)
        assertTrue(content.html.contains("Stop watch"), content.html)
        assertFalse(content.html.contains(SESSION_MODIFY_URL_IN_HTML), content.html)
    }

    @Test
    fun `a paused watch's status email carries both controls`() {
        val content = status(WatchStatusNotice.State.PAUSED, MAGIC_LINK_URL)

        assertTrue(content.html.contains("Manage watch"), content.html)
        assertTrue(content.html.contains("Stop watch"), content.html)
    }

    @Test
    fun `the Stop link is the manage link plus the stop action`() {
        // The page performs the stop; this URL only asks it to.
        val content = status(WatchStatusNotice.State.WATCHING, MAGIC_LINK_URL)

        assertTrue(content.html.contains("$MAGIC_LINK_URL_IN_HTML&amp;action=stop"), content.html)
        assertTrue(content.text.contains("$MAGIC_LINK_URL&action=stop"), content.text)
    }

    @Test
    fun `a stopped watch's status email offers no control link at all`() {
        // Nothing left to manage.
        val stopped = status(WatchStatusNotice.State.STOPPED, MAGIC_LINK_URL)
        val done = status(WatchStatusNotice.State.DONE, MAGIC_LINK_URL)

        assertFalse(stopped.html.contains(MAGIC_LINK_URL_IN_HTML), stopped.html)
        assertFalse(done.html.contains(MAGIC_LINK_URL_IN_HTML), done.html)
    }

    @Test
    fun `a status email with no link and no root url carries no control link`() {
        val content =
            EmailContentWatchStatusRenderer.render(
                WatchStatusNotice(
                    watchId = 42L,
                    state = WatchStatusNotice.State.WATCHING,
                    siteCount = 1,
                    siteName = "Site 100",
                    siteLoop = null,
                    campgroundName = null,
                    startDate = LocalDate.of(2026, 8, 1),
                    endDate = LocalDate.of(2026, 8, 3),
                    poiLinks = emptyList(),
                    appRootUrl = null,
                ),
                magicLinkUrl = null,
            )

        assertFalse(content.html.contains("<a href"), content.html)
    }
}
