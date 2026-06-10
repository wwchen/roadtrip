package ca.floo.roadtrip.routes

import ca.floo.roadtrip.client.MapboxDirections
import ca.floo.roadtrip.client.RouteResponse
import ca.floo.roadtrip.models.registry.PoiRegistry
import ca.floo.roadtrip.repo.RouteCache
import ca.floo.roadtrip.repo.migrate
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File as IoFile

/**
 * POST /api/pois/on-route. Corridor-only endpoint behind the trip-planner
 * "campgrounds along route" card list. No viewport, no truncation, no
 * sampling — every POI inside the buffered corridor is returned, sorted
 * by along-route distance.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PoisOnRouteRoutesTest {
    private lateinit var pg: PostgreSQLContainer<Nothing>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext
    private val testRegistry: PoiRegistry by lazy {
        PoiRegistry.load(IoFile("../config/poi-registry.yaml"))
    }

    @BeforeAll
    fun start() {
        System.setProperty("api.version", "1.44")
        val image = DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
        pg =
            PostgreSQLContainer<Nothing>(image).apply {
                withDatabaseName("roadtrip_test")
                withUsername("test")
                withPassword("test")
            }
        pg.start()
        val cfg =
            HikariConfig().apply {
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
    fun `corridor returns inside features sorted by along-route km`() =
        testApplication {
            // Three points along a Vancouver → Seattle line. Seed in
            // reverse-along-route order to confirm the response sort key.
            seed(
                listOf(
                    row("south", -122.4, 47.7, "campground"),
                    row("north", -123.05, 49.2, "campground"),
                    row("middle", -122.7, 48.4, "campground"),
                ),
            )
            val routeCache = primedRoute()
            application { routing { poisOnRouteRoutes(ctx, routeCache, testRegistry) } }

            val resp =
                client.post("/api/pois/on-route") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"waypoints":[{"lat":49.28,"lng":-123.1},{"lat":47.61,"lng":-122.33}],""" +
                            """"radius_miles":30,"categories":["campground"]}""",
                    )
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val parsed = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            // No truncation flag on the on-route shape — it returns everything.
            assertNull(parsed["truncated"])
            val features = parsed["features"]!!.jsonArray
            assertEquals(3, features.size)
            // Sorted by route_km ascending — north (closer to start) first,
            // south (closer to end) last.
            val routeKms =
                features.map {
                    it.jsonObject["properties"]!!
                        .jsonObject["route_km"]!!
                        .jsonPrimitive.content
                        .toDouble()
                }
            assertTrue(routeKms[0] < routeKms[1])
            assertTrue(routeKms[1] < routeKms[2])
        }

    @Test
    fun `corridor excludes points outside the buffered polyline`() =
        testApplication {
            seed(
                listOf(
                    row("inside", -122.7, 48.4, "campground"),
                    row("outside-east", -118.0, 47.0, "campground"),
                ),
            )
            val routeCache = primedRoute()
            application { routing { poisOnRouteRoutes(ctx, routeCache, testRegistry) } }

            val resp =
                client.post("/api/pois/on-route") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"waypoints":[{"lat":49.28,"lng":-123.1},{"lat":47.61,"lng":-122.33}],""" +
                            """"radius_miles":30}""",
                    )
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val parsed = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val coords =
                parsed["features"]!!
                    .jsonArray
                    .map {
                        val c = it.jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
                        c[0].jsonPrimitive.content.toDouble() to c[1].jsonPrimitive.content.toDouble()
                    }.toSet()
            assertEquals(setOf(-122.7 to 48.4), coords)
        }

    @Test
    fun `corridor uniques campground rows by provider campground id`() =
        testApplication {
            seed(
                listOf(
                    row(
                        sourceId = "recgov-near",
                        lon = -122.7,
                        lat = 48.4,
                        category = "campground",
                        source = "federal-campgrounds",
                        providerRefJson = """{"recgov_id":"12345"}""",
                    ),
                    row(
                        sourceId = "recgov-duplicate",
                        lon = -122.4,
                        lat = 47.7,
                        category = "campground",
                        source = "alternate-campgrounds",
                        providerRefJson = """{"recgov_id":"12345"}""",
                    ),
                    row(
                        sourceId = "recgov-other",
                        lon = -123.05,
                        lat = 49.2,
                        category = "campground",
                        source = "federal-campgrounds",
                        providerRefJson = """{"recgov_id":"67890"}""",
                    ),
                ),
            )
            val routeCache = primedRoute()
            application { routing { poisOnRouteRoutes(ctx, routeCache, testRegistry) } }

            val resp =
                client.post("/api/pois/on-route") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"waypoints":[{"lat":49.28,"lng":-123.1},{"lat":47.61,"lng":-122.33}],""" +
                            """"radius_miles":30,"categories":["campground"]}""",
                    )
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val features = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["features"]!!.jsonArray
            assertEquals(2, features.size)
            val coords =
                features
                    .map {
                        val c = it.jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
                        c[0].jsonPrimitive.content.toDouble() to c[1].jsonPrimitive.content.toDouble()
                    }.toSet()
            assertEquals(setOf(-122.7 to 48.4, -123.05 to 49.2), coords)
        }

    @Test
    fun `route endpoint can include buffered corridor polygon`() =
        testApplication {
            val routeCache = primedRoute(token = "test-token")
            application { routing { routeRoutes(routeCache, ctx) } }

            val resp = client.get("/api/route?coords=-123.1,49.28%3B-122.33,47.61&radius_miles=5")
            assertEquals(HttpStatusCode.OK, resp.status)
            val features = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["features"]!!.jsonArray
            val routeGeometry = features[0].jsonObject["geometry"]!!.jsonObject
            val corridorGeometry = features[1].jsonObject["geometry"]!!.jsonObject
            val corridorProperties = features[1].jsonObject["properties"]!!.jsonObject
            assertEquals(2, features.size)
            assertEquals(
                "LineString",
                routeGeometry["type"]!!.jsonPrimitive.content,
            )
            assertEquals(
                "corridor",
                corridorProperties["role"]!!.jsonPrimitive.content,
            )
            assertTrue(
                corridorGeometry["type"]!!
                    .jsonPrimitive
                    .content
                    .endsWith("Polygon"),
            )
        }

    @Test
    fun `route endpoint returns structured json error for bad quoted coords`() =
        testApplication {
            val routeCache = primedRoute(token = "test-token")
            application { routing { routeRoutes(routeCache, ctx) } }

            val resp = client.get("/api/route?coords=-123.1,49.28%3Bbad%22point")

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val parsed = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_coords", parsed["error"]!!.jsonPrimitive.content)
            assertEquals(
                "point 1: 'bad\"point' is not 'lng,lat'",
                parsed["detail"]!!.jsonPrimitive.content,
            )
        }

    @Test
    fun `empty corridor returns empty feature list`() =
        testApplication {
            seed(listOf(row("far-east", -100.0, 40.0, "campground")))
            val routeCache = primedRoute()
            application { routing { poisOnRouteRoutes(ctx, routeCache, testRegistry) } }

            val resp =
                client.post("/api/pois/on-route") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"waypoints":[{"lat":49.28,"lng":-123.1},{"lat":47.61,"lng":-122.33}],""" +
                            """"radius_miles":30}""",
                    )
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val parsed = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(0, parsed["features"]!!.jsonArray.size)
        }

    @Test
    fun `radius below MIN returns 400`() =
        testApplication {
            application { routing { poisOnRouteRoutes(ctx, primedRoute(), testRegistry) } }
            val resp =
                client.post("/api/pois/on-route") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"waypoints":[{"lat":49,"lng":-123},{"lat":48,"lng":-122}],""" +
                            """"radius_miles":0.1}""",
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }

    @Test
    fun `radius above MAX returns 400`() =
        testApplication {
            application { routing { poisOnRouteRoutes(ctx, primedRoute(), testRegistry) } }
            val resp =
                client.post("/api/pois/on-route") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"waypoints":[{"lat":49,"lng":-123},{"lat":48,"lng":-122}],""" +
                            """"radius_miles":200}""",
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }

    @Test
    fun `single waypoint returns 400`() =
        testApplication {
            application { routing { poisOnRouteRoutes(ctx, primedRoute(), testRegistry) } }
            val resp =
                client.post("/api/pois/on-route") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"waypoints":[{"lat":49,"lng":-123}],"radius_miles":30}""")
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }

    @Test
    fun `routing failure returns 503`() =
        testApplication {
            // Empty route cache + null Mapbox token → directions() throws,
            // handler should surface 503.
            application { routing { poisOnRouteRoutes(ctx, RouteCache(MapboxDirections(token = null)), testRegistry) } }
            val resp =
                client.post("/api/pois/on-route") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"waypoints":[{"lat":49.28,"lng":-123.1},{"lat":47.61,"lng":-122.33}],""" +
                            """"radius_miles":30}""",
                    )
                }
            assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
        }

    /** Pre-seed a RouteCache with the Vancouver → Seattle line used by these tests. */
    private fun primedRoute(token: String? = null): RouteCache {
        val routeCache = RouteCache(MapboxDirections(token = token))
        val waypoints = listOf(-123.1 to 49.28, -122.33 to 47.61)
        routeCache.put(
            waypoints,
            RouteResponse(
                coordinates =
                    listOf(
                        listOf(-123.1, 49.28),
                        listOf(-122.7, 48.4),
                        listOf(-122.33, 47.61),
                    ),
                distanceMeters = 230_000.0,
                durationSeconds = 9_900.0,
                legs = emptyList(),
            ),
        )
        return routeCache
    }

    private data class TestRow(
        val sourceId: String,
        val category: String,
        val name: String,
        val geomGeoJson: String,
        val source: String,
        val providerRefJson: String?,
    )

    private fun row(
        sourceId: String,
        lon: Double,
        lat: Double,
        category: String,
        source: String = "test",
        providerRefJson: String? = null,
    ): TestRow =
        TestRow(
            sourceId = sourceId,
            category = category,
            name = sourceId,
            geomGeoJson = """{"type":"Point","coordinates":[$lon,$lat]}""",
            source = source,
            providerRefJson = providerRefJson,
        )

    private fun seed(rows: List<TestRow>) {
        for (r in rows) {
            ctx.execute(
                """
                INSERT INTO pois (
                    source, source_id, category, name, geom, provider_ref, fetched_at
                ) VALUES (
                    ?, ?, ?, ?,
                    ST_SetSRID(ST_GeomFromGeoJSON(?), 4326),
                    ?::jsonb,
                    '2026-06-01 00:00:00+00'::timestamptz
                )
                """.trimIndent(),
                r.source,
                r.sourceId,
                r.category,
                r.name,
                r.geomGeoJson,
                r.providerRefJson,
            )
        }
    }
}
