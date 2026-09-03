package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.model.domain.Address
import ca.floo.roadtrip.model.domain.CampgroundContact
import ca.floo.roadtrip.model.domain.CampgroundLink
import ca.floo.roadtrip.model.domain.CampgroundLocation
import ca.floo.roadtrip.model.domain.CampgroundManagement
import ca.floo.roadtrip.model.domain.CampgroundPhoto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CampflareCampgroundFieldsTest {
    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `location keeps elevation and normalizes the nested address keys`() {
        val raw =
            obj(
                """{"latitude":37.7,"longitude":-119.5,"elevation":4000,"directions":"Past the gate.",
                   "address":{"street1":"1 Park Rd","state_code":"CA","zipcode":"95389","country_code":"US",
                              "full":"1 Park Rd, CA 95389"}}""",
            )
        assertEquals(
            CampgroundLocation(
                37.7,
                -119.5,
                elevation = 4000.0,
                directions = "Past the gate.",
                address = Address(street = "1 Park Rd", state = "CA", postcode = "95389", country = "US", full = "1 Park Rd, CA 95389"),
            ),
            campflareLocation(raw, latitude = 37.7, longitude = -119.5),
        )
    }

    @Test
    fun `location without an address carries none`() {
        assertEquals(
            CampgroundLocation(1.0, 2.0),
            campflareLocation(obj("""{"latitude":1,"longitude":2,"address":{"note":"unusable"}}"""), 1.0, 2.0),
        )
    }

    @Test
    fun `photos prefer url then large medium small original and drop entries without one`() {
        val raw = obj("""{"photos":[{"original_url":"o","large_url":"l"},{"small_url":"s"},{"caption":"none"}]}""")
        assertEquals(listOf(CampgroundPhoto("l"), CampgroundPhoto("s")), campflarePhotos(raw["photos"]))
        assertEquals(emptyList(), campflarePhotos(null))
    }

    @Test
    fun `links keep title and append the Campflare source link once`() {
        val raw = obj("""{"links":[{"href":"https://nps.test","label":"NPS"},{"url":"https://campflare.test/c/1"}]}""")
        assertEquals(
            listOf(CampgroundLink("https://nps.test", title = "NPS"), CampgroundLink("https://campflare.test/c/1")),
            campflareLinks(raw["links"], sourceUrl = "https://campflare.test/c/1"),
        )
        assertEquals(
            listOf(CampgroundLink("https://campflare.test/c/2", title = CAMPFLARE_SOURCE_LINK_TITLE)),
            campflareLinks(null, sourceUrl = "https://campflare.test/c/2"),
        )
    }

    @Test
    fun `management needs an agency and carries the website`() {
        assertEquals(
            CampgroundManagement("National Park Service", website = "https://nps.test"),
            campflareManagement(obj("""{"agency_name":"National Park Service","agency_id":7,"agency_website":"https://nps.test"}""")),
        )
        assertNull(campflareManagement(obj("""{"agency_id":7}""")))
        assertNull(campflareManagement(null))
    }

    @Test
    fun `contact maps primary keys and is null when empty`() {
        assertEquals(
            CampgroundContact(phone = "555", email = "a@b.test"),
            campflareContact(obj("""{"primary_phone":"555","primary_email":"a@b.test"}""")),
        )
        assertNull(campflareContact(obj("""{"fax":"1"}""")))
    }
}
