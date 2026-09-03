package ca.floo.roadtrip.model.domain

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CampgroundColumnJsonTest {
    @Test
    fun `object round-trips and omits absent fields`() {
        val location = CampgroundLocation(51.18, -115.57, region = "AB", address = Address(city = "Banff"))
        val raw = CampgroundColumnJson.encodeObject(location)
        assertEquals("""{"latitude":51.18,"longitude":-115.57,"region":"AB","address":{"city":"Banff"}}""", raw)
        assertEquals(location, CampgroundColumnJson.decodeObject<CampgroundLocation>(raw))
    }

    @Test
    fun `absent object is the empty object on write and null on read`() {
        assertEquals("{}", CampgroundColumnJson.encodeObject<CampgroundManagement>(null))
        assertNull(CampgroundColumnJson.decodeObject<CampgroundManagement>("{}"))
        assertNull(CampgroundColumnJson.decodeObject<CampgroundManagement>("null"))
    }

    @Test
    fun `arrays round-trip and non-arrays read as empty`() {
        val links = listOf(CampgroundLink("https://a.test/", title = "A"), CampgroundLink("https://b.test/"))
        val raw = CampgroundColumnJson.encodeArray(links)
        assertEquals("""[{"url":"https://a.test/","title":"A"},{"url":"https://b.test/"}]""", raw)
        assertEquals(links, CampgroundColumnJson.decodeArray<CampgroundLink>(raw))
        assertEquals(emptyList(), CampgroundColumnJson.decodeArray<CampgroundLink>("null"))
    }

    @Test
    fun `unknown stored keys are ignored on read`() {
        val contact = CampgroundColumnJson.decodeObject<CampgroundContact>("""{"phone":"1","fax":"2"}""")
        assertEquals(CampgroundContact(phone = "1"), contact)
    }

    @Test
    fun `element helpers send the empty object and array for absent values`() {
        assertEquals(JsonObject(emptyMap()), CampgroundColumnJson.element<CampgroundContact>(null))
        assertEquals("[]", CampgroundColumnJson.elements(emptyList<CampgroundPhoto>()).toString())
    }
}
