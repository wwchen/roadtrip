package ca.floo.roadtrip.routes

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CampsiteAvailabilityRoutesParseStartTest {
    private val today: LocalDate = LocalDate.of(2026, 6, 15)
    private val horizon = 180

    @Test
    fun `null defaults to today`() {
        val result = parseStartParam(raw = null, today = today, horizonDays = horizon)
        assertEquals(StartParam.Ok(today), result)
    }

    @Test
    fun `today is accepted`() {
        val result = parseStartParam(raw = "2026-06-15", today = today, horizonDays = horizon)
        assertEquals(StartParam.Ok(today), result)
    }

    @Test
    fun `tomorrow is accepted`() {
        val result = parseStartParam(raw = "2026-06-16", today = today, horizonDays = horizon)
        assertEquals(StartParam.Ok(today.plusDays(1)), result)
    }

    @Test
    fun `last day of horizon is accepted`() {
        val result = parseStartParam(raw = today.plusDays(180).toString(), today = today, horizonDays = horizon)
        assertTrue(result is StartParam.Ok)
    }

    @Test
    fun `one day past horizon is rejected`() {
        val result = parseStartParam(raw = today.plusDays(181).toString(), today = today, horizonDays = horizon)
        assertEquals(StartParam.Invalid, result)
    }

    @Test
    fun `yesterday is rejected`() {
        val result = parseStartParam(raw = today.minusDays(1).toString(), today = today, horizonDays = horizon)
        assertEquals(StartParam.Invalid, result)
    }

    @Test
    fun `unparseable date is rejected`() {
        val result = parseStartParam(raw = "not-a-date", today = today, horizonDays = horizon)
        assertEquals(StartParam.Invalid, result)
    }

    @Test
    fun `horizon respects the provider`() {
        // Aspira (365d) accepts a date rec.gov (180d) would reject.
        val tightHorizon = parseStartParam(raw = today.plusDays(200).toString(), today = today, horizonDays = 180)
        assertEquals(StartParam.Invalid, tightHorizon)
        val wideHorizon = parseStartParam(raw = today.plusDays(200).toString(), today = today, horizonDays = 365)
        assertTrue(wideHorizon is StartParam.Ok)
    }
}
