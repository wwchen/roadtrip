package ca.floo.roadtrip.importer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PadUsParksHttpSourceTest {
    // PAD-US returns GeoJSON with feature shape consumable by ParksGeoJsonSource.
    // Trimmed: one feature, MultiPolygon geometry, the property fields the
    // importer reads (Unit_Nm, State_Nm).
    private fun page(features: List<String>) =
        """
        {
          "type": "FeatureCollection",
          "features": [${features.joinToString(",")}]
        }
        """.trimIndent()

    private val sampleFeature =
        """
        {
          "type": "Feature",
          "geometry": {
            "type": "MultiPolygon",
            "coordinates": [[[[-120.0,40.0],[-120.0,40.5],[-119.5,40.5],[-119.5,40.0],[-120.0,40.0]]]]
          },
          "properties": {
            "Unit_Nm": "Yosemite National Park",
            "State_Nm": "CA",
            "Loc_Nm": "Yosemite Valley",
            "Mang_Name": "NPS",
            "Des_Tp": "NP",
            "GIS_Acres": 759620.0
          }
        }
        """.trimIndent()

    @Test
    fun `single page response writes one feature with _fetched_at`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val client =
            HttpClient(
                MockEngine { _ ->
                    respond(
                        content = page(listOf(sampleFeature)),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            )
        val source = PadUsParksHttpSource.nationalParks()

        val out = source.fetch(client, tmp.toFile())

        assertEquals(1, out.featureCount)
        assertEquals("national-parks.geojson", out.outputFile.name)
        val root = Json.parseToJsonElement(out.outputFile.readText()).jsonObject
        assertTrue(
            root["_fetched_at"]!!
                .jsonPrimitive.content
                .matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")),
        )
        assertEquals(1, root["features"]!!.jsonArray.size)
    }

    @Test
    fun `paginates until upstream returns fewer than page rows`(
        @TempDir tmp: Path,
    ) = runBlocking {
        // First call returns 1000 features (a "full" page), second returns
        // 250 (partial), so the paginator should stop after the second.
        val featureBlock = List(1000) { sampleFeature }
        val partialBlock = List(250) { sampleFeature }
        var calls = 0
        val client =
            HttpClient(
                MockEngine { _ ->
                    calls++
                    val body =
                        if (calls == 1) {
                            page(featureBlock)
                        } else {
                            page(partialBlock)
                        }
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            )
        // sleepMs=0 — no need to wait between mock pages in a unit test.
        val source =
            PadUsParksHttpSource(
                name = "padus-state-parks",
                whereClause = "Des_Tp='SP'",
                outputName = "state-parks.geojson",
                sleepMs = 0,
            )

        val out = source.fetch(client, tmp.toFile())

        assertEquals(2, calls, "expected exactly two upstream calls (full page + partial page)")
        assertEquals(1250, out.featureCount)
    }

    @Test
    fun `empty first page short-circuits with zero features`(
        @TempDir tmp: Path,
    ) = runBlocking {
        var calls = 0
        val client =
            HttpClient(
                MockEngine { _ ->
                    calls++
                    respond(
                        content = page(emptyList()),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            )
        val source =
            PadUsParksHttpSource(
                name = "padus-state-parks",
                whereClause = "x",
                outputName = "state-parks.geojson",
                sleepMs = 0,
            )

        val out = source.fetch(client, tmp.toFile())

        assertEquals(1, calls)
        assertEquals(0, out.featureCount)
    }
}
