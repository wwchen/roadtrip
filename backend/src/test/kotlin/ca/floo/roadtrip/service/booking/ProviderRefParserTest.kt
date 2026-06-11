package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.models.ProviderRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderRefParserTest {
    @Test
    fun `parses recgov`() {
        val ref = ProviderRefParser.parse("""{"recgov_id": "232450"}""")
        assertTrue(ref is ProviderRef.RecGov)
        assertEquals("232450", ref.recgovId)
    }

    @Test
    fun `parses aspira with all three ids as Long`() {
        val ref =
            ProviderRefParser.parse(
                """{"transactionLocationId": 9876543210, "mapId": 5550000001, "resourceLocationId": 42}""",
            )
        assertTrue(ref is ProviderRef.Aspira)
        assertEquals(9876543210L, ref.transactionLocationId)
        assertEquals(5550000001L, ref.mapId)
        assertEquals(42L, ref.resourceLocationId)
    }

    @Test
    fun `parses aspira with null resourceLocationId`() {
        val ref =
            ProviderRefParser.parse(
                """{"transactionLocationId": 100, "mapId": 200, "resourceLocationId": null}""",
            )
        assertTrue(ref is ProviderRef.Aspira)
        assertEquals(null, ref.resourceLocationId)
    }

    @Test
    fun `parses camis`() {
        val ref = ProviderRefParser.parse("""{"facility_id": "AB-12"}""")
        assertTrue(ref is ProviderRef.Camis)
        assertEquals("AB-12", ref.facilityId)
    }

    @Test
    fun `null on malformed JSON`() {
        assertNull(ProviderRefParser.parse("{not json"))
        assertNull(ProviderRefParser.parse(""))
    }

    @Test
    fun `null on unknown shape`() {
        assertNull(ProviderRefParser.parse("""{"foo": "bar"}"""))
    }

    @Test
    fun `aspira with only mapId returns null`() {
        // The legacy parser accepted this; the writer has always emitted both
        // ids. Strict shape catches data drift early.
        assertNull(ProviderRefParser.parse("""{"mapId": 100}"""))
    }
}
