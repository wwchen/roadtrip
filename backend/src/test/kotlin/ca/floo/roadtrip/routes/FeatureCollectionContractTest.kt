package ca.floo.roadtrip.routes

import ca.floo.roadtrip.repo.OnRouteRow
import ca.floo.roadtrip.repo.PoiDetailRow
import ca.floo.roadtrip.repo.PoiRow
import kotlin.test.Test
import kotlin.test.assertEquals

// Byte-identical contract test for the serialized FeatureCollection +
// per-row detail JSON. The webapp depends on the exact wire shapes:
//
//   - buildFeatureCollection (slim): drives map rendering. Only id +
//     geometry + {category, subcategory?} per feature. Anything richer
//     would inflate the bbox payload and undo the perf refactor.
//   - buildSingleFeatureJson (wide): drives popup/drawer hydration via
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
                    lng = -115.547,
                    lat = 51.1812,
                ),
            )
        val expected = (
            """{"type":"FeatureCollection","truncated":false,"features":[""" +
                """{"type":"Feature","id":42,""" +
                """"geometry":{"type":"Point","coordinates":[-115.547,51.1812]},""" +
                """"properties":{"category":"campground","subcategory":"federal"}}""" +
                """]}"""
        )
        assertEquals(expected, buildFeatureCollection(rows, truncated = false))
    }

    @Test
    fun `slim feature collection — null subcategory is omitted (PF, parks, SC)`() {
        val rows =
            listOf(
                PoiRow(
                    id = 1,
                    category = "planet-fitness",
                    subcategory = null,
                    lng = -123.0,
                    lat = 49.0,
                ),
            )
        val out = buildFeatureCollection(rows, truncated = false)
        assert(!out.contains("subcategory"))
        assert(out.contains(""""category":"planet-fitness""""))
    }

    @Test
    fun `truncated true is reflected verbatim`() {
        val out = buildFeatureCollection(emptyList(), truncated = true)
        assertEquals("""{"type":"FeatureCollection","truncated":true,"features":[]}""", out)
    }

    @Test
    fun `on-route feature collection carries route_km on each feature`() {
        val rows =
            listOf(
                OnRouteRow(
                    id = 7,
                    category = "campground",
                    subcategory = "federal",
                    lng = -122.7,
                    lat = 48.4,
                    routeKm = 95.5,
                ),
            )
        val expected = (
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","id":7,""" +
                """"geometry":{"type":"Point","coordinates":[-122.7,48.4]},""" +
                """"properties":{"category":"campground","subcategory":"federal","route_km":95.5}}""" +
                """]}"""
        )
        assertEquals(expected, encodeOnRouteJson(onRouteFeatureCollection(rows)))
    }

    @Test
    fun `on-route empty input produces empty feature list with no truncated flag`() {
        val out = encodeOnRouteJson(onRouteFeatureCollection(emptyList()))
        assertEquals("""{"type":"FeatureCollection","features":[]}""", out)
    }

    @Test
    fun `single feature detail — all optional fields populated`() {
        val row =
            PoiDetailRow(
                id = 42,
                source = "uscampgrounds",
                sourceId = "abc-123",
                category = "campground",
                subcategory = "federal",
                name = "Tunnel Mountain Village I",
                region = "AB",
                unitName = "Banff",
                reserveUrl = "https://reservation.pc.gc.ca",
                phone = "1-877-737-3783",
                infoUrl = "https://parks.canada.ca/banff",
                addressJson = """{"city":"Banff","state":"AB"}""",
                geomJson = """{"type":"Point","coordinates":[-115.547,51.1812]}""",
                propertiesJson = """{"category":"federal","amenities":["showers"]}""",
            )
        val expected = (
            """{"type":"Feature","id":42,""" +
                """"geometry":{"type":"Point","coordinates":[-115.547,51.1812]},""" +
                """"properties":{"source":"uscampgrounds","source_id":"abc-123",""" +
                """"category":"campground","subcategory":"federal",""" +
                """"name":"Tunnel Mountain Village I",""" +
                """"region":"AB","unit_name":"Banff",""" +
                """"reserve_url":"https://reservation.pc.gc.ca",""" +
                """"phone":"1-877-737-3783","info_url":"https://parks.canada.ca/banff",""" +
                """"address":{"city":"Banff","state":"AB"},""" +
                """"raw":{"category":"federal","amenities":["showers"]}}}"""
        )
        assertEquals(expected, buildSingleFeatureJson(row))
    }

    @Test
    fun `single feature detail — null optional fields omitted`() {
        val row =
            PoiDetailRow(
                id = 1,
                source = "osm",
                sourceId = "node/1",
                category = "planet-fitness",
                subcategory = null,
                name = "PF Vancouver",
                region = null,
                unitName = null,
                reserveUrl = null,
                phone = null,
                infoUrl = null,
                addressJson = null,
                geomJson = """{"type":"Point","coordinates":[-123.0,49.0]}""",
                propertiesJson = """{}""",
            )
        val out = buildSingleFeatureJson(row)
        assert(!out.contains("\"subcategory\""))
        assert(!out.contains("\"region\""))
        assert(!out.contains("\"unit_name\""))
        assert(!out.contains("\"reserve_url\""))
        assert(!out.contains("\"phone\""))
        assert(!out.contains("\"info_url\""))
        assert(!out.contains("\"address\""))
    }

    @Test
    fun `single feature detail — name with quote and backslash is escaped`() {
        val row =
            PoiDetailRow(
                id = 7,
                source = "test",
                sourceId = "x",
                category = "campground",
                subcategory = null,
                name = """O'Brien "the\backslash" Park""",
                region = null,
                unitName = null,
                reserveUrl = null,
                phone = null,
                infoUrl = null,
                addressJson = null,
                geomJson = """{"type":"Point","coordinates":[0,0]}""",
                propertiesJson = """{}""",
            )
        val out = buildSingleFeatureJson(row)
        kotlinx.serialization.json.Json
            .parseToJsonElement(out)
        assert(out.contains("""\"the\\backslash\""""))
    }
}
