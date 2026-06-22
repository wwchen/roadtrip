package ca.floo.roadtrip.clients.reserveamerica

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ReserveAmericaAvailabilityParserTest {
    @Test
    fun `parses live-style campsite calendar rows`() {
        val parsed =
            ReserveAmericaAvailabilityParser.parse(
                html =
                    """
                    <span id='resulttotal_dr_top'>67</span>
                    <div class='siteListLabel'>
                      <a href="/campsiteDetails.do?contractCode=NY&amp;siteId=253481&amp;parkId=489">042</a>
                    </div>
                    <div class='siteListLabel'>
                      <a href="/campsiteDetails.do?contractCode=NY&amp;siteId=253488&amp;parkId=489">049</a>
                    </div>
                    <div class='td status r'>R</div>
                    <div class='td status a'>
                      <a class='avail' aria-label='A for 049 on Jun 23'>A</a>
                    </div>
                    <div class='td status w'>W</div>
                    <div class='td status u'>U</div>
                    """.trimIndent(),
                startDate = LocalDate.parse("2026-06-22"),
                endDate = LocalDate.parse("2026-06-26"),
            )

        assertEquals(67, parsed.totalSites)
        assertEquals(setOf("253488"), parsed.statuses.keys)
        assertEquals(
            mapOf(
                LocalDate.parse("2026-06-22") to AvailabilityStatus.RESERVED,
                LocalDate.parse("2026-06-23") to AvailabilityStatus.AVAILABLE,
                LocalDate.parse("2026-06-24") to AvailabilityStatus.FIRST_COME,
                LocalDate.parse("2026-06-25") to AvailabilityStatus.CLOSED,
            ),
            parsed.statuses["253488"],
        )
    }
}
