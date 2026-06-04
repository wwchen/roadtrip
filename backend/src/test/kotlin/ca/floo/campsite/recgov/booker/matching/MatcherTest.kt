package ca.floo.campsite.recgov.booker.matching

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatcherTest {

    @Test
    fun `monthsInRange spans calendar months inclusive`() {
        assertEquals(
            listOf("2026-04-01", "2026-05-01", "2026-06-01"),
            Matcher.monthsInRange("2026-04-15", "2026-06-02"),
        )
        assertEquals(listOf("2026-07-01"), Matcher.monthsInRange("2026-07-10", "2026-07-20"))
    }

    @Test
    fun `findConsecutiveWindows returns every minNight slice of a run`() {
        val avail = mapOf(
            "2026-07-01T00:00:00Z" to "Available",
            "2026-07-02T00:00:00Z" to "Available",
            "2026-07-03T00:00:00Z" to "Available",
            "2026-07-04T00:00:00Z" to "Reserved",
            "2026-07-05T00:00:00Z" to "Available",
        )
        val windows = Matcher.findConsecutiveWindows(avail, "2026-07-01", "2026-07-06", 2)
        // run of 3 from 7/1..7/3 yields two 2-night windows; 7/5 alone yields none
        assertEquals(listOf(
            listOf("2026-07-01", "2026-07-02"),
            listOf("2026-07-02", "2026-07-03"),
        ), windows)
    }

    @Test
    fun `findConsecutiveWindows skips runs shorter than minNights`() {
        val avail = mapOf(
            "2026-07-01T00:00:00Z" to "Available",
            "2026-07-02T00:00:00Z" to "Reserved",
            "2026-07-03T00:00:00Z" to "Available",
        )
        assertTrue(Matcher.findConsecutiveWindows(avail, "2026-07-01", "2026-07-04", 2).isEmpty())
    }

    @Test
    fun `endDate is exclusive`() {
        val avail = mapOf(
            "2026-07-01T00:00:00Z" to "Available",
            "2026-07-02T00:00:00Z" to "Available",
        )
        // window [7/1, 7/2) — only 7/1 is in range, can't form 2-night window
        assertTrue(Matcher.findConsecutiveWindows(avail, "2026-07-01", "2026-07-02", 2).isEmpty())
    }

    @Test
    fun `passesCampsiteFilters honors campsite type filter`() {
        assertTrue(Matcher.passesCampsiteFilters(
            campsiteType = "STANDARD", site = "A1", maxNumPeople = 6, equipmentTypes = emptyList(),
            alertCampsiteTypes = listOf("STANDARD", "TENT ONLY"),
            alertEquipmentTypes = emptyList(), alertSpecificSites = emptyList(), alertMaxPeople = null,
        ))
        assertFalse(Matcher.passesCampsiteFilters(
            campsiteType = "GROUP", site = "A1", maxNumPeople = 6, equipmentTypes = emptyList(),
            alertCampsiteTypes = listOf("STANDARD"),
            alertEquipmentTypes = emptyList(), alertSpecificSites = emptyList(), alertMaxPeople = null,
        ))
    }

    @Test
    fun `passesCampsiteFilters honors specific sites and capacity`() {
        assertFalse(Matcher.passesCampsiteFilters(
            campsiteType = "STANDARD", site = "A1", maxNumPeople = 4, equipmentTypes = emptyList(),
            alertCampsiteTypes = emptyList(), alertEquipmentTypes = emptyList(),
            alertSpecificSites = listOf("B7"), alertMaxPeople = null,
        ))
        assertFalse(Matcher.passesCampsiteFilters(
            campsiteType = "STANDARD", site = "A1", maxNumPeople = 4, equipmentTypes = emptyList(),
            alertCampsiteTypes = emptyList(), alertEquipmentTypes = emptyList(),
            alertSpecificSites = emptyList(), alertMaxPeople = 6,
        ))
    }

    @Test
    fun `passesCampsiteFilters requires equipment overlap when alert lists equipment`() {
        assertTrue(Matcher.passesCampsiteFilters(
            campsiteType = "STANDARD", site = "A1", maxNumPeople = 6,
            equipmentTypes = listOf("RV", "TENT"),
            alertCampsiteTypes = emptyList(),
            alertEquipmentTypes = listOf("RV"),
            alertSpecificSites = emptyList(), alertMaxPeople = null,
        ))
        assertFalse(Matcher.passesCampsiteFilters(
            campsiteType = "STANDARD", site = "A1", maxNumPeople = 6,
            equipmentTypes = listOf("TENT"),
            alertCampsiteTypes = emptyList(),
            alertEquipmentTypes = listOf("RV"),
            alertSpecificSites = emptyList(), alertMaxPeople = null,
        ))
    }
}
