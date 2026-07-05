package ca.floo.roadtrip.clients.reserveamerica

import kotlin.test.Test
import kotlin.test.assertEquals

class ReserveAmericaCatalogParserTest {
    @Test
    fun `parses site roster from campsite calendar rows`() {
        val html =
            """
            <span id='resulttotal_dr_top'>67</span>
            <div class='siteListLabel'><a href="/camping/woodland-valley/r/campsiteDetails.do?contractCode=NY&amp;siteId=253478&amp;parkId=489" aria-label='Site: 039 (253478)'>039</a></div>
            <div class='siteListLabel'><a href="/camping/woodland-valley/r/campsiteDetails.do?contractCode=NY&amp;siteId=253497&amp;parkId=489" aria-label='Site: 056 (253497)'>056</a></div>
            """.trimIndent()

        val sites = ReserveAmericaCatalogParser.parse(html)

        assertEquals(
            listOf(
                ReserveAmericaCatalogParser.CatalogSite(parkId = "489", siteId = "253478", name = "039"),
                ReserveAmericaCatalogParser.CatalogSite(parkId = "489", siteId = "253497", name = "056"),
            ),
            sites,
        )
    }

    @Test
    fun `skips rows without a siteId`() {
        val html = "<div class='siteListLabel'><a href='/camping/x'>bogus</a></div>"
        assertEquals(emptyList(), ReserveAmericaCatalogParser.parse(html))
    }
}
