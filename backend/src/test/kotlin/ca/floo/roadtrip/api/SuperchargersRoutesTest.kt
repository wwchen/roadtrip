package ca.floo.roadtrip.api

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuperchargersRoutesTest {
    @Test
    fun `bbox filter excludes features outside`(
        @TempDir tmp: Path,
    ) = testApplication {
        val data = tmp.resolve("data").toFile().also { it.mkdirs() }
        // Three SCs: SF, NYC, LA. Bbox covers California only.
        File(data, "tesla-superchargers.geojson").writeText(
            scFc(
                listOf(
                    Triple("sf", -122.42, 37.77),
                    Triple("nyc", -73.99, 40.73),
                    Triple("la", -118.24, 34.05),
                ),
            ),
        )
        application { routing { superchargersRoutes(data) } }

        val resp =
            client.post("/api/superchargers") {
                contentType(ContentType.Application.Json)
                setBody("""{"bbox":[-125,32,-114,42]}""")
            }
        assertEquals(HttpStatusCode.OK, resp.status)
        val fc = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val ids =
            fc["features"]!!.jsonArray.map {
                it.jsonObject["properties"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.content
            }
        assertEquals(setOf("sf", "la"), ids.toSet())
        assertNull(fc["corridor"], "no corridor flag without polygon")
    }

    @Test
    fun `polygon filter restricts to corridor`(
        @TempDir tmp: Path,
    ) = testApplication {
        val data = tmp.resolve("data").toFile().also { it.mkdirs() }
        // Three SCs: inside corridor, outside-but-in-bbox, far away.
        File(data, "tesla-superchargers.geojson").writeText(
            scFc(
                listOf(
                    Triple("inside", 0.5, 0.5),
                    Triple("in_bbox_only", 5.0, 5.0),
                    Triple("far", 50.0, 50.0),
                ),
            ),
        )
        application { routing { superchargersRoutes(data) } }

        // 0..1 square corridor, with a generous bbox so 'in_bbox_only' would
        // be returned by bbox alone but is excluded by the polygon.
        val body =
            """
            {
              "bbox": [-1, -1, 10, 10],
              "polygon": {
                "type": "Polygon",
                "coordinates": [[[0,0],[1,0],[1,1],[0,1],[0,0]]]
              }
            }
            """.trimIndent()
        val resp =
            client.post("/api/superchargers") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        assertEquals(HttpStatusCode.OK, resp.status)
        val fc = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val ids =
            fc["features"]!!.jsonArray.map {
                it.jsonObject["properties"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.content
            }
        assertEquals(listOf("inside"), ids)
        assertEquals(true, fc["corridor"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `missing bbox is 400`(
        @TempDir tmp: Path,
    ) = testApplication {
        val data = tmp.resolve("data").toFile().also { it.mkdirs() }
        File(data, "tesla-superchargers.geojson").writeText(scFc(emptyList()))
        application { routing { superchargersRoutes(data) } }

        val resp =
            client.post("/api/superchargers") {
                contentType(ContentType.Application.Json)
                setBody("""{}""")
            }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("bbox"))
    }

    @Test
    fun `polygon over vertex cap is 400`(
        @TempDir tmp: Path,
    ) = testApplication {
        val data = tmp.resolve("data").toFile().also { it.mkdirs() }
        File(data, "tesla-superchargers.geojson").writeText(scFc(emptyList()))
        application { routing { superchargersRoutes(data) } }

        // 2001 vertices > MAX (2000). Build a thin spiral-ish ring.
        val n = 2001
        val pts = (0 until n).joinToString(",") { i -> "[${i * 0.001},${i * 0.001}]" } + ",[0,0]"
        val body = """{"bbox":[-1,-1,1,1],"polygon":{"type":"Polygon","coordinates":[[$pts]]}}"""
        val resp =
            client.post("/api/superchargers") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `missing data file returns empty FC`(
        @TempDir tmp: Path,
    ) = testApplication {
        val data = tmp.resolve("data").toFile().also { it.mkdirs() }
        // No file written.
        application { routing { superchargersRoutes(data) } }

        val resp =
            client.post("/api/superchargers") {
                contentType(ContentType.Application.Json)
                setBody("""{"bbox":[-180,-90,180,90]}""")
            }
        assertEquals(HttpStatusCode.OK, resp.status)
        val fc = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(0, fc["features"]!!.jsonArray.size)
    }

    private fun scFc(items: List<Triple<String, Double, Double>>): String {
        val features =
            items.joinToString(",") { (id, lng, lat) ->
                """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lng,$lat]},"properties":{"id":"$id","name":"$id","color":"#e82127"}}"""
            }
        return """{"type":"FeatureCollection","features":[$features]}"""
    }
}
