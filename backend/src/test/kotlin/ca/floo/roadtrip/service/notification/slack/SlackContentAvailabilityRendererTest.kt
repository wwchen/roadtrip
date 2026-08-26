package ca.floo.roadtrip.service.notification.slack

import ca.floo.roadtrip.client.slack.SlackAttachmentDto
import ca.floo.roadtrip.client.slack.SlackBlockDto
import ca.floo.roadtrip.service.notification.common.WatchOpening
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlackContentAvailabilityRendererTest {
    private val start = LocalDate.of(2026, 8, 1)
    private val end = LocalDate.of(2026, 8, 3)
    private val watchId = 9L
    private val appRoot = "https://app.test"

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

    private fun attach(rendered: Pair<String, List<SlackAttachmentDto>>): SlackAttachmentDto {
        assertEquals(1, rendered.second.size)
        return rendered.second.single()
    }

    private fun blocks(rendered: Pair<String, List<SlackAttachmentDto>>): List<SlackBlockDto> = attach(rendered).blocks

    /** The site-lines section (the section with mrkdwn bullets, not the headline). */
    private fun siteListSection(blocks: List<SlackBlockDto>): String =
        blocks
            .filter { it.type == "section" && it.text != null }
            .map { it.text!!.text }
            .single { it.contains("🟢") || it.contains("more") }

    private fun actionsBlock(blocks: List<SlackBlockDto>): SlackBlockDto = blocks.single { it.type == "actions" }

    @Test
    fun `single campground names the campground in the fallback and shows a Reserve primary URL button`() {
        val longName = "Group Equestrian Camp Area — Upper Meadow Loop Reservation Site A (RV or Tent, 40ft)"
        val rendered = SlackContentAvailabilityRenderer.openings(watchId, start, end, listOf(opening(label = longName)), appRoot)

        assertEquals("🏕️ 1 site just opened at Kirk Creek (2026-08-01 → 2026-08-03)", rendered.first)
        assertEquals(SlackWatchCard.COLOR_AVAIL, attach(rendered).color, "openings alert = --rt-avail green bar")

        val actions = actionsBlock(blocks(rendered)).elements!!.jsonArray
        val reserve =
            actions
                .map { it.jsonObject }
                .single { it["action_id"]?.jsonPrimitive?.content == SlackWatchCard.ACTION_RESERVE_SITE }
        assertEquals("primary", reserve["style"]?.jsonPrimitive?.content, "Reserve is the primary CTA (Slack green)")
        assertEquals("https://example.test/book/100", reserve["url"]!!.jsonPrimitive.content, "URL button to Recreation.gov")
        val label = reserve["text"]!!.jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(label.length <= 75, "label was ${label.length} chars: $label")
    }

    @Test
    fun `distinct campgrounds detected by id even when names are null - Reserve button omitted across parks`() {
        val openings =
            listOf(
                opening(label = "A1", campgroundId = 1L, campground = null),
                opening(label = "B1", campgroundId = 2L, campground = null),
            )
        val rendered = SlackContentAvailabilityRenderer.openings(watchId, start, end, openings, appRoot)

        assertEquals("🏕️ 2 sites just opened (2026-08-01 → 2026-08-03)", rendered.first)
        assertTrue(siteListSection(blocks(rendered)).contains("A1"))
        assertTrue(siteListSection(blocks(rendered)).contains("B1"))
        // A single Reserve CTA across parks would be arbitrary, so it is omitted.
        val actions = actionsBlock(blocks(rendered)).elements!!.jsonArray
        assertNull(
            actions.map { it.jsonObject }.firstOrNull { it["action_id"]?.jsonPrimitive?.content == SlackWatchCard.ACTION_RESERVE_SITE },
        )
    }

    @Test
    fun `site list is capped with a summary and clamped to Slack's section limit`() {
        val openings = (1..12).map { opening(label = "Site $it", campgroundId = 1L, bookingUrl = null) }
        val rendered = SlackContentAvailabilityRenderer.openings(watchId, start, end, openings, appRoot)

        // Cap is 3 per the design spec ("3 + N more"), not 10 like the old renderer.
        assertEquals("🏕️ 12 sites just opened at Kirk Creek (2026-08-01 → 2026-08-03)", rendered.first)
        val section = siteListSection(blocks(rendered))
        assertTrue(section.contains("+ 9 more"), section)

        val longOpenings = (1..10).map { opening(label = "Site ${"X".repeat(400)}$it", campgroundId = 1L) }
        val longRendered = SlackContentAvailabilityRenderer.openings(watchId, start, end, longOpenings, appRoot)
        assertTrue(siteListSection(blocks(longRendered)).length <= 3000)
    }

    @Test
    fun `actions row includes watch controls and routable button values`() {
        val rendered = SlackContentAvailabilityRenderer.openings(watchId, start, end, listOf(opening()), appRoot)
        val buttons =
            actionsBlock(blocks(rendered))
                .elements!!
                .jsonArray
                .map { it.jsonObject }
        val ids = buttons.mapNotNull { it["action_id"]?.jsonPrimitive?.content }

        assertTrue(SlackWatchCard.ACTION_WATCH_PAUSE in ids, "openings alert offers Pause")
        assertTrue(SlackWatchCard.ACTION_WATCH_DELETE in ids, "openings alert offers Delete")
        assertTrue(SlackWatchCard.ACTION_WATCH_RESUME !in ids, "alert fires only for active watches, so never Resume")
        buttons.forEach { assertEquals(watchId.toString(), it["value"]!!.jsonPrimitive.content) }
        val del = buttons.single { it["action_id"]?.jsonPrimitive?.content == SlackWatchCard.ACTION_WATCH_DELETE }
        assertEquals("danger", del["style"]?.jsonPrimitive?.content)
        assertTrue(del["confirm"] != null, "Delete needs a confirm dialog per the design spec")
    }

    @Test
    fun `modify button deep-links the watches page editor`() {
        // Pins the exact URL: it is a published contract the watches page's
        // useUrlAction parses, now built by watchModifyUrl rather than inline.
        val rendered = SlackContentAvailabilityRenderer.openings(watchId, start, end, listOf(opening()), appRoot)
        val modify =
            actionsBlock(blocks(rendered))
                .elements!!
                .jsonArray
                .map { it.jsonObject }
                .single { it["action_id"]?.jsonPrimitive?.content == SlackWatchCard.ACTION_OPEN_WATCHES }
        assertEquals("https://app.test/watches?action=modify&id=9", modify["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Availability grid button is dropped when the web app is unconfigured`() {
        val rendered = SlackContentAvailabilityRenderer.openings(watchId, start, end, listOf(opening()), appRootUrl = null)
        val ids =
            actionsBlock(blocks(rendered))
                .elements!!
                .jsonArray
                .mapNotNull { it.jsonObject["action_id"]?.jsonPrimitive?.content }
        assertTrue(SlackWatchCard.ACTION_OPEN_GRID !in ids)
        // Interactive buttons still render — the missing host doesn't sabotage
        // pause/delete.
        assertTrue(SlackWatchCard.ACTION_WATCH_PAUSE in ids)
    }
}
