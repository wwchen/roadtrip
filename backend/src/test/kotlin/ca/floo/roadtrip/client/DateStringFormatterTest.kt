package ca.floo.roadtrip.client

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DateStringFormatterTest {
    @Test
    fun `formats availability log dates with readable month names`() {
        assertEquals("2026/July", DateStringFormatter.month(LocalDate.parse("2026-07-01")))
        assertEquals("2026/July", DateStringFormatter.month("2026-07-01"))
        assertEquals("2026/July/06", DateStringFormatter.date(LocalDate.parse("2026-07-06")))
    }

    @Test
    fun `keeps raw month text when parsing fails`() {
        assertEquals("not-a-month", DateStringFormatter.month("not-a-month"))
    }
}
