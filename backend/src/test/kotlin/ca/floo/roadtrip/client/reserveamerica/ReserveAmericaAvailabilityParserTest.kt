package ca.floo.roadtrip.client.reserveamerica

import ca.floo.roadtrip.fixtures.mangledAvailabilityFixture
import ca.floo.roadtrip.model.availability.AvailabilityPayloadException
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun `a page with neither site rows nor a result total is unreadable, not a park with no sites`() {
        val thrown =
            assertFailsWith<AvailabilityPayloadException> {
                ReserveAmericaAvailabilityParser.parse(
                    html = mangledAvailabilityFixture("reserveamerica-session-expired.html"),
                    startDate = LocalDate.parse("2026-06-22"),
                    endDate = LocalDate.parse("2026-06-26"),
                )
            }

        assertEquals("reserveamerica", thrown.provider)
        assertTrue(thrown.message!!.contains("siteListLabel"), thrown.message)
        assertTrue(thrown.message!!.contains("Session Expired"), thrown.message)
    }

    @Test
    fun `site rows that carry no status cell at all are unreadable`() {
        val thrown =
            assertFailsWith<AvailabilityPayloadException> {
                ReserveAmericaAvailabilityParser.parse(
                    html =
                        """
                        <span id='resulttotal_dr_top'>1</span>
                        <div class='siteListLabel'>
                          <a href="/campsiteDetails.do?contractCode=NY&amp;siteId=253481&amp;parkId=489">042</a>
                        </div>
                        <div class='td state r'>R</div>
                        """.trimIndent(),
                    startDate = LocalDate.parse("2026-06-22"),
                    endDate = LocalDate.parse("2026-06-23"),
                )
            }

        assertTrue(thrown.message!!.contains("status"), thrown.message)
    }

    @Test
    fun `a result total of zero with no rows is a well-formed empty page`() {
        val parsed =
            ReserveAmericaAvailabilityParser.parse(
                html = "<span id='resulttotal_dr_top'>0</span>",
                startDate = LocalDate.parse("2026-06-22"),
                endDate = LocalDate.parse("2026-06-26"),
            )

        assertEquals(0, parsed.totalSites)
        assertEquals(emptyMap(), parsed.statuses)
    }

    @Test
    fun `call the park is classified, and an unlisted cell code is counted verbatim`() {
        val parsed =
            ReserveAmericaAvailabilityParser.parse(
                html =
                    """
                    <div class='siteListLabel'>
                      <a href="/campsiteDetails.do?contractCode=ABPP&amp;siteId=35162&amp;parkId=330174">G1</a>
                    </div>
                    <div class='td status p sun'>P</div>
                    <div class='td status q sun'>Q</div>
                    <div class='td status q'>Q</div>
                    <div class='td status a'>A</div>
                    """.trimIndent(),
                startDate = LocalDate.parse("2026-07-05"),
                endDate = LocalDate.parse("2026-07-09"),
            )

        assertEquals(
            mapOf(
                LocalDate.parse("2026-07-05") to AvailabilityStatus.UNKNOWN,
                LocalDate.parse("2026-07-06") to AvailabilityStatus.UNKNOWN,
                LocalDate.parse("2026-07-07") to AvailabilityStatus.UNKNOWN,
                LocalDate.parse("2026-07-08") to AvailabilityStatus.AVAILABLE,
            ),
            parsed.statuses["35162"],
        )
        assertEquals(mapOf("q/Q" to 2), parsed.unrecognizedStatuses)
    }
}
