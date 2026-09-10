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
    fun `element helpers send the empty object and array for absent values`() {
        assertEquals(JsonObject(emptyMap()), CatalogColumnJson.element<CampgroundContact>(null))
        assertEquals("[]", CatalogColumnJson.elements(emptyList<CatalogPhoto>()).toString())
    }
}
