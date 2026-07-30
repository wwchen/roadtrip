package ca.floo.roadtrip.service.notification.slack

import ca.floo.roadtrip.client.slack.SlackAttachmentDto
import ca.floo.roadtrip.client.slack.SlackBlockDto
import ca.floo.roadtrip.service.notification.common.WatchStatusNotice
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
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
        poiLinks: List<WatchStatusNotice.PoiLink> =
            listOf(
                WatchStatusNotice.PoiLink(
                    poiId = 7,
                    mapUrl = "https://app.test/?poi=7",
                    gridUrl = "https://grafana.test/d/campground-detail?var-poi_id=7",
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
        // The grid link must name a dashboard that exists — see
        // scripts/validate_grafana_dashboards.py for the CI-side guard.
        assertTrue(text.contains("/d/campground-detail?var-poi_id=7"), text)
        assertTrue(text.contains("View on map"), text)
        assertTrue(text.contains("?poi=7"), text)
    }

    @Test
    fun `watching card exposes Pause and Delete as interactive buttons and no Resume`() {
        val rendered = SlackContentWatchStatusRenderer.render(notice())
        val actions = blocks(rendered).single { it.type == "actions" }.elements!!.jsonArray
        val buttons = actions.map { it.jsonObject }
        val actionIds = buttons.map { it["action_id"]!!.jsonPrimitive.content }.toSet()

        assertTrue(SlackWatchCard.ACTION_WATCH_PAUSE in actionIds, "pause available while watching")
        assertTrue(SlackWatchCard.ACTION_WATCH_DELETE in actionIds, "delete is always the escape hatch")
        assertTrue(SlackWatchCard.ACTION_WATCH_RESUME !in actionIds, "resume is nonsensical while watching")
        buttons
            .filter { it["url"] == null }
            .forEach { assertEquals("42", it["value"]!!.jsonPrimitive.content, "interactive buttons route by watchId") }
        val del = buttons.single { it["action_id"]?.jsonPrimitive?.content == SlackWatchCard.ACTION_WATCH_DELETE }
        assertEquals("danger", del["style"]?.jsonPrimitive?.content, "delete uses Slack's danger red")
        assertTrue(del["confirm"] != null, "delete must have a confirm dialog so a stray tap doesn't lose the watch")
    }

    @Test
    fun `paused state renders a gray bar and a Resume primary button in place of Pause`() {
        val rendered = SlackContentWatchStatusRenderer.render(notice(state = WatchStatusNotice.State.PAUSED))
        assertEquals(SlackWatchCard.COLOR_MUTED, attach(rendered).color, "paused = gray bar")
        assertEquals(
            listOf(
                SlackWatchCard.ACTION_OPEN_GRID,
                SlackWatchCard.ACTION_OPEN_MAP,
                SlackWatchCard.ACTION_WATCH_RESUME,
                SlackWatchCard.ACTION_WATCH_DELETE,
            ),
            actionIds(rendered),
        )

        val actions = blocks(rendered).single { it.type == "actions" }.elements!!.jsonArray
        val resume = actions.map { it.jsonObject }.single { it["action_id"]?.jsonPrimitive?.content == SlackWatchCard.ACTION_WATCH_RESUME }
        // Resume is the primary CTA when a watch is paused — Slack's `primary`
        // green button style (there is no brand-blue filled button in Slack).
        assertEquals("primary", resume["style"]?.jsonPrimitive?.content)
        assertTrue(actions.none { it.jsonObject["action_id"]?.jsonPrimitive?.content == SlackWatchCard.ACTION_WATCH_PAUSE })
    }

    @Test
    fun `state variants render their color status and action contract`() {
        data class StateCase(
            val state: WatchStatusNotice.State,
            val color: String,
            val expectedActions: Set<String>,
            val missingActions: Set<String>,
            val expectedText: String,
        )

        val cases =
            listOf(
                StateCase(
                    WatchStatusNotice.State.UNCHECKED,
                    SlackWatchCard.COLOR_WATCHING,
                    setOf(SlackWatchCard.ACTION_WATCH_PAUSE, SlackWatchCard.ACTION_WATCH_DELETE),
                    setOf(SlackWatchCard.ACTION_WATCH_RESUME),
                    "not checked yet",
                ),
                StateCase(
                    WatchStatusNotice.State.DONE,
                    SlackWatchCard.COLOR_AVAIL,
                    setOf(SlackWatchCard.ACTION_WATCH_DELETE),
                    setOf(SlackWatchCard.ACTION_WATCH_PAUSE, SlackWatchCard.ACTION_WATCH_RESUME),
                    "Watch complete",
                ),
                StateCase(
                    WatchStatusNotice.State.STOPPED,
                    SlackWatchCard.COLOR_MUTED,
                    emptySet(),
                    setOf(SlackWatchCard.ACTION_WATCH_PAUSE, SlackWatchCard.ACTION_WATCH_RESUME, SlackWatchCard.ACTION_WATCH_DELETE),
                    "Watch stopped",
                ),
            )

        cases.forEach { case ->
            val rendered = SlackContentWatchStatusRenderer.render(notice(state = case.state))
            assertEquals(case.color, attach(rendered).color, case.state.name)
            assertTrue(allText(rendered).contains(case.expectedText), allText(rendered))
            val ids =
                blocks(rendered)
                    .firstOrNull { it.type == "actions" }
                    ?.elements
                    ?.jsonArray
                    ?.mapNotNull { it.jsonObject["action_id"]?.jsonPrimitive?.content }
                    .orEmpty()
                    .toSet()
            case.expectedActions.forEach { assertTrue(it in ids, "${case.state} should expose $it") }
            case.missingActions.forEach { assertTrue(it !in ids, "${case.state} should not expose $it") }
        }
    }

    @Test
    fun `scope labels prefer specific site or campground names over raw counts`() {
        val siteText = allText(SlackContentWatchStatusRenderer.render(notice(siteCount = 1, siteName = "072", siteLoop = "Upper Pines")))
        assertTrue(siteText.contains("072"), siteText)
        assertTrue(siteText.contains("Upper Pines"), siteText)
        assertTrue(!siteText.contains("1 sites"), siteText)

        val campgroundText = allText(SlackContentWatchStatusRenderer.render(notice(siteCount = 360, campgroundName = "Deception Pass")))
        assertTrue(campgroundText.contains("Campground"), campgroundText)
        assertTrue(campgroundText.contains("Deception Pass"), campgroundText)
        assertTrue(!campgroundText.contains("360"), "campground scope shouldn't surface the raw site count")
    }

    @Test
    fun `deep-link buttons follow configured hosts without dropping watch controls`() {
        val rendered = SlackContentWatchStatusRenderer.render(notice(poiLinks = emptyList()))
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

        val mapOnly =
            SlackContentWatchStatusRenderer.render(
                notice(
                    poiLinks = listOf(WatchStatusNotice.PoiLink(poiId = 7, mapUrl = "https://app.test/?poi=7", gridUrl = null)),
                ),
            )
        val mapOnlyText = allText(mapOnly)
        assertTrue(mapOnlyText.contains("?poi=7"), mapOnlyText)
        assertTrue(!mapOnlyText.contains("/d/"), "no Grafana grid link when unconfigured")
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
}
