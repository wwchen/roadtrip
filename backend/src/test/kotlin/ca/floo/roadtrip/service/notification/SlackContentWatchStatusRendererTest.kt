package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.slack.SlackAttachmentDto
import ca.floo.roadtrip.clients.slack.SlackBlockDto
import kotlinx.serialization.json.JsonObject
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

class SlackContentWatchStatusRendererTest {
    private val start = LocalDate.of(2026, 7, 11)
    private val end = LocalDate.of(2026, 7, 12)

    private fun notice(
        watchId: Long = 42,
        state: WatchStatusNotice.State = WatchStatusNotice.State.WATCHING,
        siteCount: Int = 235,
        siteName: String? = null,
        siteLoop: String? = null,
        campgroundName: String? = null,
        dashboardUrl: String? = "https://grafana.test/d/reservable-watch-drill?var-watch_id=42",
        poiLinks: List<WatchStatusNotice.PoiLink> =
            listOf(
                WatchStatusNotice.PoiLink(
                    poiId = 7,
                    mapUrl = "https://app.test/?poi=7",
                    gridUrl = "https://grafana.test/d/availability-cell-matrix?var-poi_id=7",
                ),
            ),
    ) = WatchStatusNotice(
        watchId = watchId,
        state = state,
        siteCount = siteCount,
        siteName = siteName,
        siteLoop = siteLoop,
        campgroundName = campgroundName,
        startDate = start,
        endDate = end,
        dashboardUrl = dashboardUrl,
        poiLinks = poiLinks,
    )

    private fun attach(rendered: Pair<String, List<SlackAttachmentDto>>): SlackAttachmentDto {
        assertEquals(1, rendered.second.size, "watch status card is one attachment")
        return rendered.second.single()
    }

    private fun blocks(rendered: Pair<String, List<SlackAttachmentDto>>): List<SlackBlockDto> = attach(rendered).blocks

    /** Every renderable string: fallback + each block's text/fields + button
     *  labels and URLs — so a single search can assert a payload piece is
     *  present without knowing whether it landed as text or a button. */
    private fun allText(rendered: Pair<String, List<SlackAttachmentDto>>): String =
        buildString {
            append(rendered.first)
            blocks(rendered).forEach { b ->
                b.text?.let { append('\n').append(it.text) }
                b.fields?.forEach { append('\n').append(it.text) }
                // Actions/context elements are heterogeneous JsonElements; flatten
                // any string values (button label, url, action_id, mrkdwn text)
                // into the dump. `text` is a nested object on a button but a
                // plain string on a context element, so type-check both shapes.
                b.elements?.jsonArray?.forEach { e ->
                    val obj = e.jsonObject
                    when (val t = obj["text"]) {
                        is JsonObject -> (t["text"] as? JsonPrimitive)?.contentIfString()?.let { append('\n').append(it) }
                        is JsonPrimitive -> t.contentIfString()?.let { append('\n').append(it) }
                        else -> Unit
                    }
                    (obj["url"] as? JsonPrimitive)?.contentIfString()?.let { append('\n').append(it) }
                    (obj["action_id"] as? JsonPrimitive)?.contentIfString()?.let { append('\n').append(it) }
                    (obj["value"] as? JsonPrimitive)?.contentIfString()?.let { append('\n').append(it) }
                }
            }
        }

    private fun JsonPrimitive.contentIfString(): String? = if (isString) content else null

    private fun actionIds(rendered: Pair<String, List<SlackAttachmentDto>>): List<String> =
        blocks(rendered)
            .single { it.type == "actions" }
            .elements!!
            .jsonArray
            .mapNotNull { it.jsonObject["action_id"]?.jsonPrimitive?.content }

    @Test
    fun `watching state renders a blue color bar with header, scope, window, and deep-link buttons`() {
        val rendered = SlackContentWatchStatusRenderer.render(notice())
        assertEquals(SlackWatchCard.COLOR_WATCHING, attach(rendered).color, "watching = blue --rt-brand bar")

        val text = allText(rendered)
        assertTrue(text.contains("Watching for openings"), text)
        assertTrue(text.contains("235"), text)
        assertTrue(text.contains("2026-07-11 → 2026-07-12"), text)
        assertTrue(text.lowercase().contains("nothing open right now"), rendered.first)
        // Deep-link buttons render as URL buttons in the actions row, not
        // mrkdwn hyperlinks embedded in a section.
        assertTrue(text.contains("Availability grid"), text)
        assertTrue(text.contains("/d/availability-cell-matrix"), text)
        assertTrue(text.contains("View on map"), text)
        assertTrue(text.contains("?poi=7"), text)
    }

    @Test
    fun `watching card exposes Pause and Delete as interactive buttons and no Resume`() {
        val rendered = SlackContentWatchStatusRenderer.render(notice())
        val actions = blocks(rendered).single { it.type == "actions" }.elements!!.jsonArray
        val actionIds = actions.map { it.jsonObject["action_id"]!!.jsonPrimitive.content }.toSet()

        assertTrue(SlackWatchCard.ACTION_WATCH_PAUSE in actionIds, "pause available while watching")
        assertTrue(SlackWatchCard.ACTION_WATCH_DELETE in actionIds, "delete is always the escape hatch")
        assertTrue(SlackWatchCard.ACTION_WATCH_RESUME !in actionIds, "resume is nonsensical while watching")
    }

    @Test
    fun `paused state renders a gray bar and a Resume primary button in place of Pause`() {
        val rendered = SlackContentWatchStatusRenderer.render(notice(state = WatchStatusNotice.State.PAUSED))
        assertEquals(SlackWatchCard.COLOR_MUTED, attach(rendered).color, "paused = gray bar")

        val actions = blocks(rendered).single { it.type == "actions" }.elements!!.jsonArray
        val resume = actions.map { it.jsonObject }.single { it["action_id"]?.jsonPrimitive?.content == SlackWatchCard.ACTION_WATCH_RESUME }
        // Resume is the primary CTA when a watch is paused — Slack's `primary`
        // green button style (there is no brand-blue filled button in Slack).
        assertEquals("primary", resume["style"]?.jsonPrimitive?.content)
        assertTrue(actions.none { it.jsonObject["action_id"]?.jsonPrimitive?.content == SlackWatchCard.ACTION_WATCH_PAUSE })
    }

    @Test
    fun `paused status card keeps Resume in the same slot where Pause lived`() {
        val rendered = SlackContentWatchStatusRenderer.render(notice(state = WatchStatusNotice.State.PAUSED))

        assertEquals(
            listOf(
                SlackWatchCard.ACTION_OPEN_GRID,
                SlackWatchCard.ACTION_OPEN_MAP,
                SlackWatchCard.ACTION_OPEN_DASHBOARD,
                SlackWatchCard.ACTION_WATCH_RESUME,
                SlackWatchCard.ACTION_WATCH_DELETE,
            ),
            actionIds(rendered),
        )
    }

    @Test
    fun `done state renders a green bar and only a Delete button`() {
        val rendered = SlackContentWatchStatusRenderer.render(notice(state = WatchStatusNotice.State.DONE))
        assertEquals(SlackWatchCard.COLOR_AVAIL, attach(rendered).color)

        val actionIds =
            blocks(rendered)
                .single { it.type == "actions" }
                .elements!!
                .jsonArray
                .mapNotNull { it.jsonObject["action_id"]?.jsonPrimitive?.content }
        assertTrue(SlackWatchCard.ACTION_WATCH_DELETE in actionIds)
        assertTrue(SlackWatchCard.ACTION_WATCH_PAUSE !in actionIds)
        assertTrue(SlackWatchCard.ACTION_WATCH_RESUME !in actionIds)
    }

    @Test
    fun `stopped state renders a gray bar and no actions row (terminal card)`() {
        val rendered = SlackContentWatchStatusRenderer.render(notice(state = WatchStatusNotice.State.STOPPED))
        assertEquals(SlackWatchCard.COLOR_MUTED, attach(rendered).color)
        assertTrue(blocks(rendered).none { it.type == "actions" }, "stopped card is terminal — no buttons")
        assertTrue(allText(rendered).contains("Watch stopped"), allText(rendered))
    }

    @Test
    fun `unchecked state renders like watching (blue bar) and reports first check pending`() {
        val rendered = SlackContentWatchStatusRenderer.render(notice(state = WatchStatusNotice.State.UNCHECKED))
        assertEquals(SlackWatchCard.COLOR_WATCHING, attach(rendered).color)
        assertTrue(allText(rendered).contains("not checked yet"), allText(rendered))
    }

    @Test
    fun `a single-site watch names the site and its loop instead of a count`() {
        val text = allText(SlackContentWatchStatusRenderer.render(notice(siteCount = 1, siteName = "072", siteLoop = "Upper Pines")))
        assertTrue(text.contains("072"), text)
        assertTrue(text.contains("Upper Pines"), text)
        assertTrue(!text.contains("1 sites"), text)
    }

    @Test
    fun `a whole-campground watch names the campground instead of a raw site count`() {
        val text = allText(SlackContentWatchStatusRenderer.render(notice(siteCount = 360, campgroundName = "Deception Pass")))
        assertTrue(text.contains("Campground"), text)
        assertTrue(text.contains("Deception Pass"), text)
        assertTrue(!text.contains("360"), "campground scope shouldn't surface the raw site count")
    }

    @Test
    fun `an active watch card has no mrkdwn hyperlinks — deep links are URL buttons`() {
        // The old design embedded <url|label> hyperlinks in section text; the
        // new design promotes them to Block Kit URL buttons so the card
        // matches the design system's actions-row layout.
        val sectionText =
            blocks(SlackContentWatchStatusRenderer.render(notice()))
                .filter { it.type == "section" && it.text != null }
                .joinToString("\n") { it.text!!.text }
        assertTrue(!sectionText.contains("<http"), "no <url|label> hyperlinks in section text: $sectionText")
    }

    @Test
    fun `no configured hosts renders no deep-link buttons`() {
        val rendered = SlackContentWatchStatusRenderer.render(notice(dashboardUrl = null, poiLinks = emptyList()))
        val text = allText(rendered)
        assertTrue(!text.contains("/d/"), "no Grafana links when unconfigured")
        assertTrue(!text.contains("?poi="), "no map links when the web app is unconfigured")
        // The actions row still carries the mutation buttons (Pause + Delete)
        // — the deep-link absence must not drop them.
        val actionIds =
            blocks(rendered)
                .single { it.type == "actions" }
                .elements!!
                .jsonArray
                .mapNotNull { it.jsonObject["action_id"]?.jsonPrimitive?.content }
        assertTrue(SlackWatchCard.ACTION_WATCH_PAUSE in actionIds)
        assertTrue(SlackWatchCard.ACTION_WATCH_DELETE in actionIds)
    }

    @Test
    fun `a POI with only a map link (Grafana unconfigured) still exposes the map button`() {
        val rendered =
            SlackContentWatchStatusRenderer.render(
                notice(
                    dashboardUrl = null,
                    poiLinks = listOf(WatchStatusNotice.PoiLink(poiId = 7, mapUrl = "https://app.test/?poi=7", gridUrl = null)),
                ),
            )
        val text = allText(rendered)
        assertTrue(text.contains("?poi=7"), text)
        assertTrue(!text.contains("/d/"), "no Grafana grid link when unconfigured")
    }

    @Test
    fun `every interactive button carries the watchId in value so the endpoint can route`() {
        val rendered = SlackContentWatchStatusRenderer.render(notice(watchId = 1234))
        val actions = blocks(rendered).single { it.type == "actions" }.elements!!.jsonArray
        val interactive =
            actions.map { it.jsonObject }.filter { it["url"] == null }
        assertTrue(interactive.isNotEmpty(), "at least one interactive button")
        interactive.forEach { btn ->
            assertEquals("1234", btn["value"]!!.jsonPrimitive.content, "value must carry the watchId for routing")
        }
    }

    @Test
    fun `Delete button is danger-styled and carries a confirm dialog`() {
        val rendered = SlackContentWatchStatusRenderer.render(notice())
        val actions = blocks(rendered).single { it.type == "actions" }.elements!!.jsonArray
        val del = actions.map { it.jsonObject }.single { it["action_id"]?.jsonPrimitive?.content == SlackWatchCard.ACTION_WATCH_DELETE }
        assertEquals("danger", del["style"]?.jsonPrimitive?.content, "delete uses Slack's danger red")
        assertNotNull(del["confirm"], "delete must have a confirm dialog so a stray tap doesn't lose the watch")
    }

    @Test
    fun `card ends with a muted context sub-line describing the watch state`() {
        val rendered = SlackContentWatchStatusRenderer.render(notice())
        val ctx = blocks(rendered).lastOrNull { it.type == "context" }
        assertNotNull(ctx, "context sub-line missing")
        val ctxText =
            ctx.elements!!
                .jsonArray
                .first()
                .jsonObject["text"]!!
                .jsonPrimitive.content
        assertTrue(ctxText.contains("Armed"), ctxText)
    }

    @Test
    fun `an over-long single-site name is clamped so the post is never rejected`() {
        val longName = "x".repeat(5_000)
        val rendered = SlackContentWatchStatusRenderer.render(notice(siteCount = 1, siteName = longName))
        val field =
            blocks(rendered)
                .first { it.type == "section" && it.fields != null }
                .fields!!
                .first { it.text.contains("x") }
        assertTrue(field.text.length <= 2_000, "field was ${field.text.length} chars")
    }

    @Test
    fun `fallback text still reads as a complete sentence`() {
        val (fallback, _) = SlackContentWatchStatusRenderer.render(notice())
        // Slack notifications show this fallback on mobile push, in Do Not
        // Disturb, and in any client that doesn't render blocks. Must survive
        // block-less rendering with the state clearly readable.
        assertTrue(fallback.contains("Watching"), fallback)
        assertTrue(fallback.contains("235"), fallback)
        assertTrue(fallback.contains("2026-07-11"), fallback)
    }

    @Test
    fun `null dashboardUrl doesn't emit a dashboard button`() {
        val rendered = SlackContentWatchStatusRenderer.render(notice(dashboardUrl = null))
        val actions = blocks(rendered).single { it.type == "actions" }.elements!!.jsonArray
        assertNull(
            actions.map { it.jsonObject }.firstOrNull {
                it["action_id"]?.jsonPrimitive?.content ==
                    SlackWatchCard.ACTION_OPEN_DASHBOARD
            },
        )
    }
}
