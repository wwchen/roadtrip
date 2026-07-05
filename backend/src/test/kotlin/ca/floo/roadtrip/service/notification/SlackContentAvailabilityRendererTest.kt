package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.slack.SlackBlockDto
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlackContentAvailabilityRendererTest {
    private val start = LocalDate.of(2026, 8, 1)
    private val end = LocalDate.of(2026, 8, 3)

    private fun opening(
        label: String = "Site 100",
        campgroundId: Long? = 1L,
        campground: String? = "Kirk Creek",
        bookingUrl: String? = "https://example.test/book/100",
    ) = WatchOpening(
        label = label,
        loop = null,
        siteType = null,
        date = start,
        campgroundId = campgroundId,
        campground = campground,
        bookingUrl = bookingUrl,
    )

    /** The site-lines section (the first mrkdwn section, before any link section). */
    private fun sectionText(blocks: List<SlackBlockDto>) = blocks.first { it.type == "section" && it.text != null }.text!!.text

    /** The Reserve CTA renders as an emphasized mrkdwn hyperlink (`*<url|label>*`)
     *  in its own section — not a Block Kit button. Returns its raw markup, or
     *  null when no Reserve link was emitted. */
    private fun reserveLinkMarkup(blocks: List<SlackBlockDto>): String? =
        blocks
            .filter { it.type == "section" && it.text != null }
            .map { it.text!!.text }
            .firstOrNull { it.contains("|Reserve") }

    /** The visible label inside `<url|label>` markup. */
    private fun linkLabel(markup: String) = markup.substringAfter('|').substringBeforeLast('>')

    @Test
    fun `single campground names the campground in the fallback and shows a Reserve link`() {
        val (fallback, blocks) = SlackContentAvailabilityRenderer.openings(start, end, listOf(opening()))

        assertEquals("⛺ 1 campsite available at Kirk Creek", fallback)
        val markup = reserveLinkMarkup(blocks)!!
        assertTrue(markup.contains("<https://example.test/book/100|"), markup)
        assertTrue(linkLabel(markup).startsWith("Reserve"), markup)
    }

    @Test
    fun `Reserve link label never exceeds the readable 75 char budget`() {
        val longName = "Group Equestrian Camp Area — Upper Meadow Loop Reservation Site A (RV or Tent, 40ft)"
        val (_, blocks) = SlackContentAvailabilityRenderer.openings(start, end, listOf(opening(label = longName)))

        val label = linkLabel(reserveLinkMarkup(blocks)!!)
        assertTrue(label.length <= 75, "link label was ${label.length} chars: $label")
        assertTrue(label.endsWith("…") || label.contains(longName.take(10)), label)
    }

    @Test
    fun `distinct campgrounds are detected by id even when names are null, and the link is dropped`() {
        val openings =
            listOf(
                opening(label = "A1", campgroundId = 1L, campground = null),
                opening(label = "B1", campgroundId = 2L, campground = null),
            )
        val (fallback, blocks) = SlackContentAvailabilityRenderer.openings(start, end, openings)

        // Two parks collapse to neither a single "at X" nor a bogus single campground.
        assertEquals("⛺ 2 campsites available", fallback)
        assertTrue(sectionText(blocks).contains("A1") && sectionText(blocks).contains("B1"))
        // A single Reserve CTA across parks would be arbitrary, so it is omitted.
        assertNull(reserveLinkMarkup(blocks), "no Reserve link across multiple campgrounds")
    }

    @Test
    fun `the sites section is clamped to Slack's 3000 char limit`() {
        // 10 sites (the cap) with very long names would blow past the section limit.
        val openings = (1..10).map { opening(label = "Site ${"X".repeat(400)}$it", campgroundId = 1L) }
        val (_, blocks) = SlackContentAvailabilityRenderer.openings(start, end, openings)

        assertTrue(sectionText(blocks).length <= 3000, "section text was ${sectionText(blocks).length} chars")
    }

    @Test
    fun `more than the cap of sites is summarized`() {
        val openings = (1..12).map { opening(label = "Site $it", campgroundId = 1L, bookingUrl = null) }
        val (fallback, blocks) = SlackContentAvailabilityRenderer.openings(start, end, openings)

        assertEquals("⛺ 12 campsites available at Kirk Creek", fallback)
        assertTrue(sectionText(blocks).contains("…and 2 more"), sectionText(blocks))
    }
}
