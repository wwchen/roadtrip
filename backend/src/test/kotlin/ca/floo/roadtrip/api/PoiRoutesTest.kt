package ca.floo.roadtrip.api

import ca.floo.roadtrip.importer.Category
import ca.floo.roadtrip.importer.Importer
import ca.floo.roadtrip.importer.Source
import ca.floo.roadtrip.importer.StagedPoi
import ca.floo.roadtrip.importer.migrate
import ca.floo.roadtrip.importer.pointGeoJson
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PoiRoutesTest {

    private lateinit var pg: PostgreSQLContainer<Nothing>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext

    @BeforeAll
    fun start() {
        System.setProperty("api.version", "1.44")
        val image = DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
        pg = PostgreSQLContainer<Nothing>(image).apply {
            withDatabaseName("roadtrip_test")
            withUsername("test")
            withPassword("test")
        }
        pg.start()
        val cfg = HikariConfig().apply {
            jdbcUrl = pg.jdbcUrl
            username = pg.username
            password = pg.password
            maximumPoolSize = 2
        }
        ds = HikariDataSource(cfg)
        migrate(ds)
        ctx = DSL.using(ds, SQLDialect.POSTGRES)
    }

    @AfterAll
    fun stop() {
        ds.close()
        pg.stop()
    }

    @BeforeEach
    fun reset() {
        ctx.execute("DELETE FROM pois")
        ctx.execute("DELETE FROM import_runs")
    }

    @Test
    fun `bbox returns matching points only`() = testApplication {
        seed(listOf(
            row("inside-1", "Vancouver Park", -123.0, 49.0, Category.CAMPGROUND),
            row("inside-2", "Whistler Camp", -122.95, 50.1, Category.CAMPGROUND),
            row("outside-fl", "Miami Park", -80.0, 25.0, Category.CAMPGROUND),
        ))
        application { routing { poiRoutes(ctx) } }

        val resp = client.get("/api/pois?bbox=-125,47,-120,51")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("FeatureCollection", body["type"]!!.jsonPrimitive.content)
        assertEquals(false, body["truncated"]!!.jsonPrimitive.boolean)
        val features = body["features"]!!.jsonArray
        assertEquals(2, features.size)
        val names = features.map { it.jsonObject["properties"]!!.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("Vancouver Park", "Whistler Camp"), names)
    }

    @Test
    fun `bbox over empty water returns empty FeatureCollection with truncated false`() = testApplication {
        // Mid-Pacific envelope, far from anything seeded. Must come back as a
        // valid empty FeatureCollection — not an error, not a missing field.
        seed(listOf(
            row("vancouver", "Vancouver Park", -123.0, 49.0, Category.CAMPGROUND),
        ))
        application { routing { poiRoutes(ctx) } }

        val resp = client.get("/api/pois?bbox=-160,5,-150,15")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("FeatureCollection", body["type"]!!.jsonPrimitive.content)
        assertEquals(false, body["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(0, body["features"]!!.jsonArray.size)
    }

    @Test
    fun `category filter narrows the set`() = testApplication {
        seed(listOf(
            row("camp-1", "Camp A", -123.0, 49.0, Category.CAMPGROUND),
            row("park-1", "State Park A", -123.05, 49.05, Category.STATE_PARK),
            row("pf-1", "PF Vancouver", -123.1, 49.1, Category.PLANET_FITNESS),
        ))
        application { routing { poiRoutes(ctx) } }

        val resp = client.get("/api/pois?bbox=-125,47,-120,51&category=campground,state-park")
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val cats = body["features"]!!.jsonArray
            .map { it.jsonObject["properties"]!!.jsonObject["category"]!!.jsonPrimitive.content }
            .toSet()
        assertEquals(setOf("campground", "state-park"), cats)
    }

    @Test
    fun `truncation kicks in above the cap`() = testApplication {
        // Seed POI_LIMIT + 5 rows in a tight bbox.
        val rows = (1..(POI_LIMIT + 5)).map { i ->
            row(
                sourceId = "bulk-$i-${"%04x".format(i)}",
                name = "Site $i",
                lon = -123.0 + (i * 0.0001),
                lat = 49.0 + (i * 0.0001),
                category = Category.CAMPGROUND,
            )
        }
        seed(rows)
        application { routing { poiRoutes(ctx) } }

        val body = Json.parseToJsonElement(client.get("/api/pois?bbox=-125,47,-120,51").bodyAsText()).jsonObject
        assertEquals(true, body["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(POI_LIMIT, body["features"]!!.jsonArray.size)
    }

    @Test
    fun `malformed bbox returns 400`() = testApplication {
        application { routing { poiRoutes(ctx) } }

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/pois").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/pois?bbox=not,a,real,bbox").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/pois?bbox=-125,51,-120,47").status) // s>=n
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/pois?bbox=-125,47,-120").status)
    }

    @Test
    fun `antimeridian-crossing bbox is rejected with 400`() = testApplication {
        // west=170, east=-170 (the bbox wraps the antimeridian). PostGIS
        // ST_MakeEnvelope can't express a wrapping envelope without splitting,
        // so we reject at the API layer instead of returning misleading rows.
        application { routing { poiRoutes(ctx) } }
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/pois?bbox=170,-10,-170,10").status)
    }

    @Test
    fun `health route still serves`() = testApplication {
        application { routing { poiRoutes(ctx) } }
        assertEquals(HttpStatusCode.OK, client.get("/api/pois/health").status)
    }

    @Test
    fun `polygon geometry rendered as Polygon GeoJSON`() = testApplication {
        // State park with a tiny polygon — result must come back as GeoJSON
        // Polygon, not WKT and not just the bounding point.
        val ring = "[[[-123.1,49.1],[-122.9,49.1],[-122.9,49.3],[-123.1,49.3],[-123.1,49.1]]]"
        val polygonGeoJson = """{"type":"Polygon","coordinates":$ring}"""
        seed(listOf(StagedPoi(
            sourceId = "poly-1",
            category = Category.STATE_PARK,
            name = "Polygon Park",
            geomGeoJson = polygonGeoJson,
            region = "BC",
            unitName = "Polygon Park",
            properties = buildJsonObject { put("test", true) },
            reserveUrl = null,
            fetchedAt = Instant.parse("2026-06-01T00:00:00Z"),
        )))
        application { routing { poiRoutes(ctx) } }

        val body = Json.parseToJsonElement(client.get("/api/pois?bbox=-125,47,-120,51").bodyAsText()).jsonObject
        val feat = body["features"]!!.jsonArray.single().jsonObject
        assertEquals("Polygon", feat["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    private fun row(sourceId: String, name: String, lon: Double, lat: Double, category: Category): StagedPoi =
        StagedPoi(
            sourceId = sourceId,
            category = category,
            name = name,
            geomGeoJson = pointGeoJson(lon, lat),
            region = "BC",
            unitName = null,
            properties = buildJsonObject { put("test", true) },
            reserveUrl = null,
            fetchedAt = Instant.parse("2026-06-01T00:00:00Z"),
        )

    private fun seed(rows: List<StagedPoi>) {
        // Use the importer to honor the source/source_id constraint.
        Importer(ctx).run(object : Source {
            override val name = "test"
            override fun staged() = rows.asSequence()
        })
    }
}
