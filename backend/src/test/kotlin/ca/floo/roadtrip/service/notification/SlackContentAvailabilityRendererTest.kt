package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.slack.SlackAttachmentDto
import ca.floo.roadtrip.clients.slack.SlackBlockDto
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (isString) content else null

    @Test
    fun `attachment carries the availability green bar`() {
        val rendered = SlackContentAvailabilityRenderer.openings(watchId, start, end, listOf(opening()), appRoot)
        assertEquals(SlackWatchCard.COLOR_AVAIL, attach(rendered).color, "openings alert = --rt-avail green bar")
    }

    @Test
    fun `single campground names the campground in the fallback and shows a Reserve primary URL button`() {
        val rendered = SlackContentAvailabilityRenderer.openings(watchId, start, end, listOf(opening()), appRoot)

        assertEquals("🏕️ 1 site just opened at Kirk Creek (2026-08-01 → 2026-08-03)", rendered.first)

        val actions = actionsBlock(blocks(rendered)).elements!!.jsonArray
        val reserve =
            actions
                .map { it.jsonObject }
                .single { it["action_id"]?.jsonPrimitive?.content == SlackWatchCard.ACTION_RESERVE_SITE }
        assertEquals("primary", reserve["style"]?.jsonPrimitive?.content, "Reserve is the primary CTA (Slack green)")
        assertEquals("https://example.test/book/100", reserve["url"]!!.jsonPrimitive.content, "URL button to Recreation.gov")
    }

    @Test
    fun `Reserve label never exceeds the readable 75 char budget`() {
        val longName = "Group Equestrian Camp Area — Upper Meadow Loop Reservation Site A (RV or Tent, 40ft)"
        val rendered = SlackContentAvailabilityRenderer.openings(watchId, start, end, listOf(opening(label = longName)), appRoot)

        val reserve =
            actionsBlock(blocks(rendered)).elements!!.jsonArray.map { it.jsonObject }.single {
                it["action_id"]?.jsonPrimitive?.content == SlackWatchCard.ACTION_RESERVE_SITE
            }
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
    fun `the sites section is clamped to Slack's 3000 char limit`() {
        // 10 sites (the cap) with very long names would blow past the section limit.
        val openings = (1..10).map { opening(label = "Site ${"X".repeat(400)}$it", campgroundId = 1L) }
        val rendered = SlackContentAvailabilityRenderer.openings(watchId, start, end, openings, appRoot)

        assertTrue(siteListSection(blocks(rendered)).length <= 3000)
    }

    @Test
    fun `more than the cap of sites is summarized with '+ N more'`() {
        val openings = (1..12).map { opening(label = "Site $it", campgroundId = 1L, bookingUrl = null) }
        val rendered = SlackContentAvailabilityRenderer.openings(watchId, start, end, openings, appRoot)

        // Cap is 3 per the design spec ("3 + N more"), not 10 like the old renderer.
        assertEquals("🏕️ 12 sites just opened at Kirk Creek (2026-08-01 → 2026-08-03)", rendered.first)
        val section = siteListSection(blocks(rendered))
        assertTrue(section.contains("+ 9 more"), section)
    }

    @Test
    fun `actions row always includes Pause and Delete for the openings alert (watch is active)`() {
        val rendered = SlackContentAvailabilityRenderer.openings(watchId, start, end, listOf(opening()), appRoot)
        val ids =
            actionsBlock(blocks(rendered))
                .elements!!
                .jsonArray
                .mapNotNull { it.jsonObject["action_id"]?.jsonPrimitive?.content }
        assertTrue(SlackWatchCard.ACTION_WATCH_PAUSE in ids, "openings alert offers Pause")
        assertTrue(SlackWatchCard.ACTION_WATCH_DELETE in ids, "openings alert offers Delete")
        assertTrue(SlackWatchCard.ACTION_WATCH_RESUME !in ids, "alert fires only for active watches, so never Resume")
    }

    @Test
    fun `Delete on the openings alert is danger-styled with a confirm dialog`() {
        val rendered = SlackContentAvailabilityRenderer.openings(watchId, start, end, listOf(opening()), appRoot)
        val del =
            actionsBlock(blocks(rendered))
                .elements!!
                .jsonArray
                .map { it.jsonObject }
                .single { it["action_id"]?.jsonPrimitive?.content == SlackWatchCard.ACTION_WATCH_DELETE }
        assertEquals("danger", del["style"]?.jsonPrimitive?.content)
        assertNotNull(del["confirm"], "Delete needs a confirm dialog per the design spec")
    }

    @Test
    fun `every button carries the watchId in value so the endpoint can route`() {
        val rendered = SlackContentAvailabilityRenderer.openings(watchId, start, end, listOf(opening()), appRoot)
        val actions = actionsBlock(blocks(rendered)).elements!!.jsonArray
        actions.map { it.jsonObject }.forEach { btn ->
            assertEquals(watchId.toString(), btn["value"]!!.jsonPrimitive.content)
        }
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

    @Test
    fun `site lines use the design spec's green-circle bullet, not the old markdown dot`() {
        val rendered = SlackContentAvailabilityRenderer.openings(watchId, start, end, listOf(opening()), appRoot)
        val section = siteListSection(blocks(rendered))
        assertTrue(section.contains("🟢"), "site lines lead with the availability bullet: $section")
        assertTrue(!section.startsWith("•"), "no bare mrkdwn bullet")
    }

    @Test
    fun `card ends with a muted context sub-line about Reserve going to Recreation gov`() {
        val rendered = SlackContentAvailabilityRenderer.openings(watchId, start, end, listOf(opening()), appRoot)
        val ctx = blocks(rendered).lastOrNull { it.type == "context" }
        assertNotNull(ctx, "context block missing")
        val ctxText =
            ctx.elements!!
                .jsonArray
                .first()
                .jsonObject["text"]!!
                .jsonPrimitive.content
        assertTrue(ctxText.contains("Reserve"), ctxText)
    }
}
