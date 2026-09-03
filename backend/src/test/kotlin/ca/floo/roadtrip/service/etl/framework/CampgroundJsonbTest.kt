package ca.floo.roadtrip.service.etl.framework

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class CampgroundJsonbTest {
    @Test
    fun `location omits the optional keys it was not given`() {
        val expected =
            buildJsonObject {
                put("latitude", 51.18)
                put("longitude", -115.57)
            }
        assertEquals(expected, CampgroundJsonb.location(51.18, -115.57))
    }

    @Test
    fun `location carries region, country and address in column order`() {
        val address = buildJsonObject { put("city", "Banff") }
        val expected =
            buildJsonObject {
                put("latitude", 51.18)
                put("longitude", -115.57)
                put("region", "AB")
                put("country", "CA")
                put("address", address)
            }
        assertEquals(
            expected,
            CampgroundJsonb.location(51.18, -115.57, region = "AB", country = "CA", address = address),
        )
        assertEquals(listOf("latitude", "longitude", "region", "country", "address"), expected.keys.toList())
    }

    @Test
    fun `links and photos are arrays of url objects`() {
        val expected =
            buildJsonArray {
                add(buildJsonObject { put("url", "https://a.test/") })
                add(buildJsonObject { put("url", "https://b.test/") })
            }
        assertEquals(expected, CampgroundJsonb.links(listOf("https://a.test/", "https://b.test/")))
        assertEquals(expected.take(1), CampgroundJsonb.links("https://a.test/"))
        assertEquals(expected.take(1), CampgroundJsonb.photos("https://a.test/"))
    }

    @Test
    fun `management and contact are single-key objects`() {
        assertEquals(buildJsonObject { put("agency", "Parks Canada") }, CampgroundJsonb.management("Parks Canada"))
        assertEquals(buildJsonObject { put("phone", "1-800-000-0000") }, CampgroundJsonb.contact("1-800-000-0000"))
    }
}
