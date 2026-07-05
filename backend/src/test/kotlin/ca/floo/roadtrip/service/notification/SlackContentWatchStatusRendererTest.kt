package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.slack.SlackBlockDto
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlackContentWatchStatusRendererTest {
    private val start = LocalDate.of(2026, 7, 11)
    private val end = LocalDate.of(2026, 7, 12)

    private fun notice(
        state: WatchStatusNotice.State = WatchStatusNotice.State.WATCHING,
        siteCount: Int = 235,
        siteName: String? = null,
        siteLoop: String? = null,
        dashboardUrl: String? = "https://grafana.test/d/reservable-watch-drill?var-watch_id=1",
        poiLinks: List<WatchStatusNotice.PoiLink> =
            listOf(
                WatchStatusNotice.PoiLink(
                    poiId = 7,
                    mapUrl = "https://app.test/?poi=7",
                    gridUrl = "https://grafana.test/d/availability-cell-matrix?var-poi_id=7",
                ),
            ),
    ) = WatchStatusNotice(
        state = state,
        siteCount = siteCount,
        siteName = siteName,
        siteLoop = siteLoop,
        startDate = start,
        endDate = end,
        dashboardUrl = dashboardUrl,
        poiLinks = poiLinks,
    )

    private fun header(blocks: List<SlackBlockDto>) = blocks.first { it.type == "header" }.text!!.text

    /** Every renderable string: fallback + each block's text and fields. */
    private fun allText(pair: Pair<String, List<SlackBlockDto>>) =
        buildString {
            append(pair.first)
            pair.second.forEach { b ->
                b.text?.let { append('\n').append(it.text) }
                b.fields?.forEach { append('\n').append(it.text) }
            }
        }

    @Test
    fun `watching state renders a header, scope count, window, and dashboard links`() {
        val rendered = SlackContentWatchStatusRenderer.render(notice())
        val (fallback, blocks) = rendered

        assertTrue(blocks.any { it.type == "header" }, "status card leads with a header block")
        assertTrue(header(blocks).contains("Watching"), header(blocks))
        val text = allText(rendered)
        assertTrue(text.contains("235"), text)
        assertTrue(text.contains("2026-07-11 → 2026-07-12"), text)
        assertTrue(text.contains("nothing available right now"), fallback)
        assertTrue(text.contains("/d/reservable-watch-drill"), text)
        assertTrue(text.contains("/d/availability-cell-matrix"), text)
        assertTrue(text.contains("?poi=7"), "carries the web-app POI map link")
        assertTrue(text.contains("view on map"), text)
    }

    @Test
    fun `unchecked state reports availability not checked yet`() {
        val text = allText(SlackContentWatchStatusRenderer.render(notice(state = WatchStatusNotice.State.UNCHECKED)))
        assertTrue(text.contains("not checked yet"), text)
    }

    @Test
    fun `paused state reports paused and won't alert`() {
        val (fallback, blocks) = SlackContentWatchStatusRenderer.render(notice(state = WatchStatusNotice.State.PAUSED))
        assertTrue(header(blocks).contains("paused"), header(blocks))
        assertTrue(fallback.contains("Paused"), fallback)
    }

    @Test
    fun `done state reports completion`() {
        val (fallback, blocks) = SlackContentWatchStatusRenderer.render(notice(state = WatchStatusNotice.State.DONE))
        assertTrue(header(blocks).contains("complete"), header(blocks))
        assertTrue(fallback.contains("Done"), fallback)
    }

    @Test
    fun `a single-site watch names the site and its loop instead of a count`() {
        val text =
            allText(
                SlackContentWatchStatusRenderer.render(
                    notice(siteCount = 1, siteName = "072", siteLoop = "Upper Pines"),
                ),
            )
        assertTrue(text.contains("072"), text)
        assertTrue(text.contains("Upper Pines"), text)
        assertTrue(!text.contains("1 sites"), text)
    }

    @Test
    fun `no configured hosts renders no link section`() {
        val rendered = SlackContentWatchStatusRenderer.render(notice(dashboardUrl = null, poiLinks = emptyList()))
        val text = allText(rendered)
        assertTrue(!text.contains("/d/"), "no Grafana links when unconfigured")
        assertTrue(!text.contains("?poi="), "no map links when the web app is unconfigured")
    }

    @Test
    fun `a POI with only a map link (Grafana unconfigured) still links to the map`() {
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
    fun `an over-long single-site name is clamped so the post is never rejected`() {
        val longName = "x".repeat(5_000)
        val rendered = SlackContentWatchStatusRenderer.render(notice(siteCount = 1, siteName = longName))
        val field = rendered.second.first { it.type == "section" && it.fields != null }.fields!!.first { it.text.contains("x") }
        assertTrue(field.text.length <= 2_000, "field was ${field.text.length} chars")
    }
}
