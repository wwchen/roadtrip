package ca.floo.roadtrip.model.domain

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogColumnJsonTest {
    @Test
    fun `object round-trips and omits absent fields`() {
        val location = CampgroundLocation(51.18, -115.57, region = "AB", address = Address(city = "Banff"))
        val raw = CatalogColumnJson.encodeObject(location)
        assertEquals("""{"latitude":51.18,"longitude":-115.57,"region":"AB","address":{"city":"Banff"}}""", raw)
        assertEquals(location, CatalogColumnJson.decodeObject<CampgroundLocation>(raw))
    }

    @Test
    fun `absent object is the empty object on write and null on read`() {
        assertEquals("{}", CatalogColumnJson.encodeObject<CampgroundManagement>(null))
        assertNull(CatalogColumnJson.decodeObject<CampgroundManagement>("{}"))
        assertNull(CatalogColumnJson.decodeObject<CampgroundManagement>("null"))
    }

    @Test
    fun `arrays round-trip and non-arrays read as empty`() {
        val links = listOf(CampgroundLink("https://a.test/", title = "A"), CampgroundLink("https://b.test/"))
        val raw = CatalogColumnJson.encodeArray(links)
        assertEquals("""[{"url":"https://a.test/","title":"A"},{"url":"https://b.test/"}]""", raw)
        assertEquals(links, CatalogColumnJson.decodeArray<CampgroundLink>(raw))
        assertEquals(emptyList(), CatalogColumnJson.decodeArray<CampgroundLink>("null"))
    }

    @Test
    fun `unknown stored keys are ignored on read`() {
        val contact = CatalogColumnJson.decodeObject<CampgroundContact>("""{"phone":"1","fax":"2"}""")
        assertEquals(CampgroundContact(phone = "1"), contact)
    }

    @Test
    fun `amenity keys serialize as their wire values`() {
        val amenities =
            listOf(
                CampgroundAmenity(AmenityKey.TOILETS, detail = "vault"),
                CampgroundAmenity(AmenityKey.SHOWERS, present = false),
                CampgroundAmenity(AmenityKey.OTHER, detail = "Surfing"),
            )
        val raw = CatalogColumnJson.encodeArray(amenities)
        assertEquals(
            """[{"key":"toilets","detail":"vault"},{"key":"showers","present":false},{"key":"other","detail":"Surfing"}]""",
            raw,
        )
        assertEquals(amenities, CatalogColumnJson.decodeArray<CampgroundAmenity>(raw))
        AmenityKey.entries.forEach { key ->
            assertEquals(
                listOf(CampgroundAmenity(key)),
                CatalogColumnJson.decodeArray<CampgroundAmenity>("""[{"key":"${key.wire}"}]"""),
            )
        }
    }

    @Test
    fun `carriers serialize as their wire values`() {
        val signals = listOf(CarrierSignal(Carrier.VERIZON, average = 3.5, count = 11), CarrierSignal(Carrier.US_CELLULAR, average = 2.0))
        val raw = CatalogColumnJson.encodeArray(signals)
        assertEquals("""[{"carrier":"verizon","average":3.5,"count":11},{"carrier":"uscell","average":2.0}]""", raw)
        assertEquals(signals, CatalogColumnJson.decodeArray<CarrierSignal>(raw))
        Carrier.entries.forEach { carrier ->
            assertEquals(
                listOf(CarrierSignal(carrier, average = 1.0)),
                CatalogColumnJson.decodeArray<CarrierSignal>("""[{"carrier":"${carrier.wire}","average":1.0}]"""),
            )
        }
    }

    @Test
    fun `campground metadata keeps its snake case wire keys`() {
        val metadata =
            CampgroundMetadata(
                activities = listOf("Camping", "Hiking"),
                rating = CampgroundRating(average = 4.5, count = 12),
                lastUpdated = "2026-07-01T00:00:00Z",
            )
        val raw = CatalogColumnJson.encodeObject(metadata)
        assertEquals(
            """{"activities":["Camping","Hiking"],"rating":{"average":4.5,"count":12},"last_updated":"2026-07-01T00:00:00Z"}""",
            raw,
        )
        assertEquals(metadata, CatalogColumnJson.decodeObject<CampgroundMetadata>(raw))
    }

    @Test
    fun `schedule and alert wire keys are snake case`() {
        assertEquals(
            """{"check_in":"14:00","check_out":"11:00"}""",
            CatalogColumnJson.encodeObject(CampgroundSchedule(checkIn = "14:00", checkOut = "11:00")),
        )
        val alerts = listOf(CampgroundAlert(title = "Fire ban", body = "No fires.", endsOn = "2026-09-30", sourceUrl = "https://a.test/"))
        val raw = CatalogColumnJson.encodeArray(alerts)
        assertEquals(
            """[{"title":"Fire ban","body":"No fires.","ends_on":"2026-09-30","source_url":"https://a.test/"}]""",
            raw,
        )
        assertEquals(alerts, CatalogColumnJson.decodeArray<CampgroundAlert>(raw))
    }

    @Test
    fun `element helpers send the empty object and array for absent values`() {
        assertEquals(JsonObject(emptyMap()), CatalogColumnJson.element<CampgroundContact>(null))
        assertEquals("[]", CatalogColumnJson.elements(emptyList<CatalogPhoto>()).toString())
    }
}
