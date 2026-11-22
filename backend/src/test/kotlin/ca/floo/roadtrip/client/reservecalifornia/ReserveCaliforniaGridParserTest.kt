package ca.floo.roadtrip.client.reservecalifornia

import ca.floo.roadtrip.fixtures.mangledAvailabilityFixture
import ca.floo.roadtrip.model.availability.AvailabilityPayloadException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val observedAt: Instant = Instant.parse("2026-12-01T00:00:00Z")

class ReserveCaliforniaGridParserTest {
    @Test
    fun `a grid answer without its Facility is unreadable, not facility 0 with no units`() {
        val thrown = assertUnreadable(mangledAvailabilityFixture("reservecalifornia-grid-no-facility.json"))

        assertEquals("reservecalifornia", thrown.provider)
        assertTrue(thrown.message!!.contains("Facility"), thrown.message)
    }

    @Test
    fun `a facility without its FacilityId is unreadable`() {
        val thrown = assertUnreadable("""{"Facility":{"Units":{}}}""")

        assertTrue(thrown.message!!.contains("FacilityId"), thrown.message)
    }

    @Test
    fun `a facility without its Units is unreadable`() {
        val thrown = assertUnreadable("""{"Facility":{"FacilityId":612}}""")

        assertTrue(thrown.message!!.contains("Units"), thrown.message)
    }

    @Test
    fun `a unit missing its booking flags is unreadable, not closed`() {
        val thrown =
            assertUnreadable(
                """{"Facility":{"FacilityId":612,"Units":{"u":{"UnitId":1,"IsWebViewable":true,"Slices":{}}}}}""",
            )

        assertTrue(thrown.message!!.contains("AllowWebBooking"), thrown.message)
    }

    @Test
    fun `a slice missing its ReservationId is unreadable, not unreserved`() {
        val thrown =
            assertUnreadable(
                """
                {"Facility":{"FacilityId":612,"Units":{"u":{"UnitId":1,"IsWebViewable":true,"AllowWebBooking":true,
                "Slices":{"2026-12-15T00:00:00":{"Date":"2026-12-15T00:00:00","IsFree":true,"IsBlocked":false,"IsWalkin":false,"Lock":null}}}}}}
                """.trimIndent(),
            )

        assertTrue(thrown.message!!.contains("ReservationId"), thrown.message)
    }

    @Test
    fun `a slice whose date does not parse is unreadable`() {
        val thrown =
            assertUnreadable(
                """
                {"Facility":{"FacilityId":612,"Units":{"u":{"UnitId":1,"IsWebViewable":true,"AllowWebBooking":true,
                "Slices":{"soon":{"Date":"soon","IsFree":true,"IsBlocked":false,"IsWalkin":false,"ReservationId":0,"Lock":null}}}}}}
                """.trimIndent(),
            )

        assertTrue(thrown.message!!.contains("soon"), thrown.message)
    }

    @Test
    fun `a facility with no units is a well-formed empty grid`() {
        val grid = ReserveCaliforniaGridParser.parse(payload("""{"Facility":{"FacilityId":612,"Units":{}}}"""), observedAt)

        assertEquals(612L, grid.facilityId)
        assertEquals(emptyMap(), grid.statuses)
    }

    private fun assertUnreadable(json: String): AvailabilityPayloadException =
        assertFailsWith<AvailabilityPayloadException> { ReserveCaliforniaGridParser.parse(payload(json), observedAt) }

    private fun payload(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
}
