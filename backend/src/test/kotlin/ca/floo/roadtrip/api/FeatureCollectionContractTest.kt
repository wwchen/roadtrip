package ca.floo.roadtrip.api

import kotlin.test.Test
import kotlin.test.assertEquals

// Byte-identical contract test for the hand-rolled buildFeatureCollection.
// The webapp's flattenPoi() depends on the exact field shape (id at top
// level, raw nested under properties.raw) — any drift here is a silent
// breakage in the popup and search flows. Pure unit, no DB.
class FeatureCollectionContractTest {

    @Test
    fun `single point with all optional fields populated`() {
        val rows = listOf(
            PoiRow(
                id = 42,
                source = "uscampgrounds",
                sourceId = "abc-123",
                category = "campground",
                name = "Tunnel Mountain Village I",
                region = "AB",
                unitName = "Banff",
                reserveUrl = "https://reservation.pc.gc.ca",
                geomJson = """{"type":"Point","coordinates":[-115.547,51.1812]}""",
                propertiesJson = """{"category":"federal","amenities":["showers"]}""",
            )
        )
        val expected = (
            """{"type":"FeatureCollection","truncated":false,"features":[""" +
            """{"type":"Feature","id":42,""" +
            """"geometry":{"type":"Point","coordinates":[-115.547,51.1812]},""" +
            """"properties":{"source":"uscampgrounds","source_id":"abc-123",""" +
            """"category":"campground","name":"Tunnel Mountain Village I",""" +
            """"region":"AB","unit_name":"Banff",""" +
            """"reserve_url":"https://reservation.pc.gc.ca",""" +
            """"raw":{"category":"federal","amenities":["showers"]}}}""" +
            """]}"""
            )
        assertEquals(expected, buildFeatureCollection(rows, truncated = false))
    }

    @Test
    fun `optional fields omitted when null`() {
        val rows = listOf(
            PoiRow(
                id = 1,
                source = "osm",
                sourceId = "node/1",
                category = "planet-fitness",
                name = "PF Vancouver",
                region = null,
                unitName = null,
                reserveUrl = null,
                geomJson = """{"type":"Point","coordinates":[-123.0,49.0]}""",
                propertiesJson = """{}""",
            )
        )
        val out = buildFeatureCollection(rows, truncated = false)
        // No null-valued keys leak into the wire format.
        assert(!out.contains("\"region\""))
        assert(!out.contains("\"unit_name\""))
        assert(!out.contains("\"reserve_url\""))
    }

    @Test
    fun `truncated true is reflected verbatim`() {
        val out = buildFeatureCollection(emptyList(), truncated = true)
        assertEquals("""{"type":"FeatureCollection","truncated":true,"features":[]}""", out)
    }

    @Test
    fun `name with quote and backslash is escaped`() {
        val rows = listOf(
            PoiRow(
                id = 7,
                source = "test",
                sourceId = "x",
                category = "campground",
                name = """O'Brien "the\backslash" Park""",
                region = null,
                unitName = null,
                reserveUrl = null,
                geomJson = """{"type":"Point","coordinates":[0,0]}""",
                propertiesJson = """{}""",
            )
        )
        val out = buildFeatureCollection(rows, truncated = false)
        // The escaped name must round-trip through a strict JSON parser.
        kotlinx.serialization.json.Json.parseToJsonElement(out)
        assert(out.contains("""\"the\\backslash\""""))
    }
}
