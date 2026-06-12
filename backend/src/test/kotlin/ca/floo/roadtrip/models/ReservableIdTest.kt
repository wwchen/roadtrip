package ca.floo.roadtrip.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReservableIdTest {
    @Test
    fun `parse + encode round-trips`() {
        val raw = "site:recgov:330257"
        val parsed = ReservableId.parse(raw)
        assertEquals(ReservableId(ReservableType.SITE, "recgov", "330257"), parsed)
        assertEquals(raw, parsed!!.encode())
    }

    @Test
    fun `aspira negative-int vendor_id round-trips`() {
        val raw = "site:aspira_bc:-2147483190"
        val parsed = ReservableId.parse(raw)!!
        assertEquals(ReservableType.SITE, parsed.type)
        assertEquals("aspira_bc", parsed.vendor)
        assertEquals("-2147483190", parsed.vendorId)
        assertEquals(raw, parsed.encode())
    }

    @Test
    fun `vendor_id may contain colons`() {
        // Colons inside vendor_id are preserved — split on first two colons only.
        // This matters for future ticket IDs like "arches-2026-08-01-09:00".
        val raw = "site:nps:arches-2026-08-01-09:00"
        val parsed = ReservableId.parse(raw)!!
        assertEquals("nps", parsed.vendor)
        assertEquals("arches-2026-08-01-09:00", parsed.vendorId)
        assertEquals(raw, parsed.encode())
    }

    @Test
    fun `parse normalizes type and vendor to lowercase`() {
        val parsed = ReservableId.parse("SITE:RECGOV:330257")!!
        assertEquals(ReservableType.SITE, parsed.type)
        assertEquals("recgov", parsed.vendor)
        assertEquals("330257", parsed.vendorId)
        assertEquals("site:recgov:330257", parsed.encode())
    }

    @Test
    fun `parse rejects unknown type`() {
        assertNull(ReservableId.parse("hovercraft:recgov:1"))
    }

    @Test
    fun `parse rejects missing colon`() {
        assertNull(ReservableId.parse("site:recgov"))
        assertNull(ReservableId.parse("site"))
        assertNull(ReservableId.parse(""))
    }

    @Test
    fun `parse rejects empty parts`() {
        assertNull(ReservableId.parse(":recgov:1"))
        assertNull(ReservableId.parse("site::1"))
        assertNull(ReservableId.parse("site:recgov:"))
    }

    @Test
    fun `constructor rejects empty vendor`() {
        assertFailsWith<IllegalArgumentException> {
            ReservableId(ReservableType.SITE, "", "1")
        }
    }

    @Test
    fun `constructor rejects empty vendorId`() {
        assertFailsWith<IllegalArgumentException> {
            ReservableId(ReservableType.SITE, "recgov", "")
        }
    }

    @Test
    fun `constructor rejects vendor with colon`() {
        assertFailsWith<IllegalArgumentException> {
            ReservableId(ReservableType.SITE, "rec:gov", "1")
        }
    }

    @Test
    fun `toString matches encode`() {
        val rid = ReservableId(ReservableType.SITE, "recgov", "330257")
        assertEquals("site:recgov:330257", rid.toString())
    }

    @Test
    fun `ReservableType parse round-trips`() {
        assertEquals(ReservableType.SITE, ReservableType.parse("site"))
        assertEquals(ReservableType.SITE, ReservableType.parse("SITE"))
        assertNull(ReservableType.parse("permit"))
        assertNull(ReservableType.parse(""))
    }
}
