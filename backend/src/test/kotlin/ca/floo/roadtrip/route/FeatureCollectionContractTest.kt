package ca.floo.roadtrip.route

import ca.floo.roadtrip.model.api.poi.PoiCategoryDetailSchema
import ca.floo.roadtrip.model.api.poi.PoiCtaSchema
import ca.floo.roadtrip.model.api.poi.PoiDetailFeatureSchema
import ca.floo.roadtrip.model.api.poi.PoiDetailPropertiesSchema
import ca.floo.roadtrip.model.domain.poi.PoiRow
import ca.floo.roadtrip.route.api.pois.onRouteFeatureCollection
import ca.floo.roadtrip.route.common.encodeApiJson
import ca.floo.roadtrip.service.poi.poiFeatureCollection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

// Byte-identical contract test for the serialized FeatureCollection +
// per-row detail JSON. The webapp depends on the exact wire shapes:
//
//   - poiFeatureCollection (slim): drives map rendering. Only id +
//     geometry + {category, subcategory?, agency?} per feature. Anything
//     richer would inflate the bbox payload and undo the perf refactor.
//   - poiDetailFeature (wide): drives popup/drawer hydration via
//     GET /api/pois/{id}. Same shape /api/pois used to ship inline.
//
// Pure unit, no DB.
class FeatureCollectionContractTest {
    @Test
    fun `slim feature collection — campground with subcategory`() {
        val rows =
            listOf(
                PoiRow(
                    id = 42,
                    category = "campground",
                    subcategory = "federal",
                    agency = "National Park Service",
                    lng = -115.547,
                    lat = 51.1812,
                ),
            )
        val expected = (
            """{"type":"FeatureCollection","truncated":false,"features":[""" +
                """{"type":"Feature","id":42,""" +
                """"geometry":{"type":"Point","coordinates":[-115.547,51.1812]},""" +
                """"properties":{"category":"campground","subcategory":"federal","agency":"National Park Service"}}""" +
                """]}"""
        )
        assertEquals(expected, encodeApiJson(poiFeatureCollection(rows, truncated = false)))
    }

    @Test
    fun `slim feature collection — null subcategory is omitted (PF, parks, SC)`() {
        val rows =
            listOf(
                PoiRow(
                    id = 1,
                    category = "planet-fitness",
                    subcategory = null,
                    agency = null,
                    lng = -123.0,
                    lat = 49.0,
                ),
            )
        val out = encodeApiJson(poiFeatureCollection(rows, truncated = false))
        assert(!out.contains("subcategory"))
        assert(!out.contains("agency"))
        assert(out.contains(""""category":"planet-fitness""""))
    }

    @Test
    fun `truncated true is reflected verbatim`() {
        val out = encodeApiJson(poiFeatureCollection(emptyList(), truncated = true))
        assertEquals("""{"type":"FeatureCollection","truncated":true,"features":[]}""", out)
    }

    @Test
    fun `on-route feature collection matches slim poi shape without truncation`() {
        val rows =
            listOf(
                PoiRow(
                    id = 7,
                    category = "campground",
                    subcategory = "federal",
                    agency = "National Park Service",
                    lng = -122.7,
                    lat = 48.4,
                ),
            )
        val expected = (
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","id":7,""" +
                """"geometry":{"type":"Point","coordinates":[-122.7,48.4]},""" +
                """"properties":{"category":"campground","subcategory":"federal","agency":"National Park Service"}}""" +
                """]}"""
        )
        assertEquals(expected, encodeApiJson(onRouteFeatureCollection(rows)))
    }

    @Test
    fun `on-route empty input produces empty feature list with no truncated flag`() {
        val out = encodeApiJson(onRouteFeatureCollection(emptyList()))
        assertEquals("""{"type":"FeatureCollection","features":[]}""", out)
    }

    @Test
    fun `single feature detail — all optional fields populated`() {
        val feature =
            detailFeature(
                id = 42,
                source = "uscampgrounds",
                sourceId = "abc-123",
                category = "campground",
                subcategory = "federal",
                agency = "Parks Canada",
                name = "Tunnel Mountain Village I",
                region = "AB",
                country = "CA",
                geometry = """{"type":"Point","coordinates":[-115.547,51.1812]}""",
                detail =
                    PoiCategoryDetailSchema(
                        sources = listOf("uscampgrounds", "recgov"),
                        availabilityProvider = "parks-canada",
                        timeZone = "America/Edmonton",
                        earliestDate = "2026-06-21",
                        unitName = "Banff",
                        reserveUrl = "https://reservation.pc.gc.ca",
                        bookingSite = "reservation.pc.gc.ca",
                        phone = "1-877-737-3783",
                        infoUrl = "https://parks.canada.ca/banff",
                        address = json("""{"city":"Banff","state":"AB"}"""),
                        description = "Camp among redwoods.",
                        photoUrl = "https://example.test/photo.jpg",
                        cta =
                            listOf(
                                PoiCtaSchema(
                                    url = "https://parks.canada.ca/banff",
                                    label = "Park info on parks.canada.ca",
                                    kind = "info",
                                ),
                            ),
                        raw =
                            json(
                                """{"category":"federal","amenities":["showers"],"activities":["hiking"],""" +
                                    """"sites":42,"description":"Camp among redwoods.","photo_url":"https://example.test/photo.jpg",""" +
                                    """"season":"May-Oct","near":"Banff"}""",
                            ),
                    ),
            )
        val expected = (
            """{"type":"Feature","id":42,""" +
                """"geometry":{"type":"Point","coordinates":[-115.547,51.1812]},""" +
                """"properties":{"source":"uscampgrounds","source_id":"abc-123",""" +
                """"category":"campground","subcategory":"federal",""" +
                """"agency":"Parks Canada",""" +
                """"name":"Tunnel Mountain Village I",""" +
                """"region":"AB","country":"CA",""" +
                """"detail":{"sources":["uscampgrounds","recgov"],""" +
                """"availability_provider":"parks-canada","time_zone":"America/Edmonton",""" +
                """"earliest_date":"2026-06-21","unit_name":"Banff",""" +
                """"reserve_url":"https://reservation.pc.gc.ca",""" +
                """"booking_site":"reservation.pc.gc.ca",""" +
                """"phone":"1-877-737-3783","info_url":"https://parks.canada.ca/banff",""" +
                """"address":{"city":"Banff","state":"AB"},""" +
                """"description":"Camp among redwoods.",""" +
                """"photo_url":"https://example.test/photo.jpg",""" +
                """"cta":[{"url":"https://parks.canada.ca/banff",""" +
                """"label":"Park info on parks.canada.ca","kind":"info"}],""" +
                """"raw":{"category":"federal","amenities":["showers"],"activities":["hiking"],""" +
                """"sites":42,"description":"Camp among redwoods.","photo_url":"https://example.test/photo.jpg",""" +
                """"season":"May-Oct","near":"Banff"}}}}"""
        )
        assertEquals(expected, encodeApiJson(feature))
    }

    @Test
    fun `single feature detail — null optional fields omitted`() {
        val out =
            encodeApiJson(
                detailFeature(
                    id = 1,
                    source = "osm",
                    sourceId = "node/1",
                    category = "planet-fitness",
                    subcategory = null,
                    name = "PF Vancouver",
                    region = null,
                    geometry = """{"type":"Point","coordinates":[-123.0,49.0]}""",
                    detail = PoiCategoryDetailSchema(raw = json("""{}""")),
                ),
            )
        assert(!out.contains("\"subcategory\""))
        assert(!out.contains("\"agency\""))
        assert(!out.contains("\"region\""))
        assert(!out.contains("\"unit_name\""))
        assert(!out.contains("\"reserve_url\""))
        assert(!out.contains("\"phone\""))
        assert(!out.contains("\"info_url\""))
        assert(!out.contains("\"address\""))
        assert(!out.contains("\"availability_supported\""))
        assert(!out.contains("\"cta\""))
        assert(!out.contains("\"booking_system\""))
        assert(out.contains(""""detail":{"sources":[],"raw":{}}"""))
    }

    @Test
    fun `single feature detail — availability support is provider agnostic`() {
        val out =
            encodeApiJson(
                detailFeature(
                    id = 117,
                    source = "reserveamerica-ny-campgrounds",
                    sourceId = "ra-117",
                    category = "campground",
                    subcategory = "state",
                    name = "KENNETH L. WILSON",
                    region = "NY",
                    country = "US",
                    geometry = """{"type":"Point","coordinates":[-74.2170278,42.0250833]}""",
                    detail =
                        PoiCategoryDetailSchema(
                            infoUrl =
                                "https://newyorkstateparks.reserveamerica.com/camping/x/r/campgroundDetails.do?contractCode=NY&parkId=117",
                            providerRef = json("""{"contract_code":"NY","park_id":"117"}"""),
                            availabilitySupported = true,
                            raw = json("""{}"""),
                        ),
                ),
            )
        val detail =
            Json
                .parseToJsonElement(out)
                .jsonObject["properties"]!!
                .jsonObject["detail"]!!
                .jsonObject

        assertEquals(true, detail["availability_supported"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `single feature detail — name with quote and backslash is escaped`() {
        val out =
            encodeApiJson(
                detailFeature(
                    id = 7,
                    source = "test",
                    sourceId = "x",
                    category = "campground",
                    subcategory = null,
                    name = """O'Brien "the\backslash" Park""",
                    region = null,
                    geometry = """{"type":"Point","coordinates":[0,0]}""",
                    detail = PoiCategoryDetailSchema(raw = json("""{}""")),
                ),
            )
        kotlinx.serialization.json.Json
            .parseToJsonElement(out)
        assert(out.contains("""\"the\\backslash\""""))
    }

    private fun detailFeature(
        id: Long,
        source: String,
        sourceId: String,
        category: String,
        subcategory: String?,
        agency: String? = null,
        name: String,
        region: String?,
        country: String? = null,
        geometry: String,
        detail: PoiCategoryDetailSchema,
    ): PoiDetailFeatureSchema =
        PoiDetailFeatureSchema(
            id = id,
            geometry = json(geometry),
            properties =
                PoiDetailPropertiesSchema(
                    source = source,
                    sourceId = sourceId,
                    category = category,
                    subcategory = subcategory,
                    agency = agency,
                    name = name,
                    region = region,
                    country = country,
                    detail = detail,
                ),
        )

    private fun json(raw: String) = Json.parseToJsonElement(raw)
}
