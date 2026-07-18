package ca.floo.roadtrip.route

import ca.floo.roadtrip.client.mapbox.MapboxDirections
import ca.floo.roadtrip.config.RouteConfig
import ca.floo.roadtrip.model.routing.RouteResponse
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.RouteCorridorRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.route.api.pois.poisOnRouteRoutes
import ca.floo.roadtrip.route.api.route.routeRoutes
import ca.floo.roadtrip.service.poi.CampgroundService
import ca.floo.roadtrip.service.poi.PlanetFitnessLocationService
import ca.floo.roadtrip.service.poi.PoiService
import ca.floo.roadtrip.service.poi.PoisOnRouteService
import ca.floo.roadtrip.service.poi.TeslaSuperchargerService
import ca.floo.roadtrip.service.routing.RouteCache
import ca.floo.roadtrip.service.routing.RouteCorridorService
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.Route
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * POST /api/pois/on-route. Corridor-only endpoint behind the trip-planner
 * "campgrounds along route" card list. No viewport, no truncation, no
 * sampling — every POI inside the buffered corridor is returned.
 */
class PoisOnRouteRoutesTest : SharedDbTest() {
    private val routeConfig =
        RouteConfig(
            maxWaypoints = 25,
            minCorridorRadiusMiles = 1.0,
            maxCorridorRadiusMiles = 100.0,
        )

    private fun routeCorridorService(): RouteCorridorService = RouteCorridorService(RouteCorridorRepo(ctx))

    private fun poiService(): PoiService =
        PoiService(
            poiRepo = PoiServingRepo(ctx, enabledDataProviders = emptySet()),
            detailServices =
                listOf(
                    CampgroundService(CampgroundRepo(ctx)),
                    TeslaSuperchargerService(TeslaSuperchargerRepo(ctx)),
                    PlanetFitnessLocationService(PlanetFitnessLocationRepo(ctx)),
                ),
        )

    private fun poisOnRouteService(routeCache: RouteCache): PoisOnRouteService =
        PoisOnRouteService(
            routeCache = routeCache,
            routeCorridorService = routeCorridorService(),
            poiService = poiService(),
        )

    private fun Route.testPoisOnRouteRoutes(poisOnRouteService: PoisOnRouteService) {
        poisOnRouteRoutes(poisOnRouteService, routeConfig = routeConfig)
    }

    private fun Route.testRouteRoutes(routeCache: RouteCache) {
        routeRoutes(routeCache, routeCorridorService(), routeConfig = routeConfig)
    }

    @BeforeEach
    fun reset() {
        ctx.cleanCanonicalCatalogFixtures()
        ctx.execute("DELETE FROM import_runs")
    }

    @Test
    fun `corridor returns inside slim features`() =
        testApplication {
            seed(
                listOf(
                    row("south", -122.4, 47.7, "campground"),
                    row("north", -123.05, 49.2, "campground", agency = "National Park Service"),
                    row("middle", -122.7, 48.4, "campground"),
                ),
            )
            val routeCache = primedRoute()
            application { routeTestApplication { testPoisOnRouteRoutes(poisOnRouteService(routeCache)) } }

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
            val properties = features.map { it.jsonObject["properties"]!!.jsonObject }
            assertTrue(properties.any { it["agency"]?.jsonPrimitive?.content == "National Park Service" })
            assertTrue(properties.all { "route_km" !in it })
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
            application { routeTestApplication { testPoisOnRouteRoutes(poisOnRouteService(routeCache)) } }

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
                        source = "recgov",
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
                        source = "recgov",
                        providerRefJson = """{"recgov_id":"67890"}""",
                    ),
                ),
            )
            val routeCache = primedRoute()
            application { routeTestApplication { testPoisOnRouteRoutes(poisOnRouteService(routeCache)) } }

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
            application { routeTestApplication { testRouteRoutes(routeCache) } }

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
            application { routeTestApplication { testRouteRoutes(routeCache) } }

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
            application { routeTestApplication { testPoisOnRouteRoutes(poisOnRouteService(routeCache)) } }

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
            application { routeTestApplication { testPoisOnRouteRoutes(poisOnRouteService(primedRoute())) } }
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
            application { routeTestApplication { testPoisOnRouteRoutes(poisOnRouteService(primedRoute())) } }
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
            application { routeTestApplication { testPoisOnRouteRoutes(poisOnRouteService(primedRoute())) } }
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
            application {
                routeTestApplication {
                    testPoisOnRouteRoutes(poisOnRouteService(RouteCache(MapboxDirections(token = null))))
                }
            }
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
        val agency: String?,
    )

    private fun row(
        sourceId: String,
        lon: Double,
        lat: Double,
        category: String,
        source: String = "test",
        providerRefJson: String? = null,
        agency: String? = null,
    ): TestRow =
        TestRow(
            sourceId = sourceId,
            category = category,
            name = sourceId,
            geomGeoJson = """{"type":"Point","coordinates":[$lon,$lat]}""",
            source = source,
            providerRefJson = providerRefJson,
            agency = agency,
        )

    private fun seed(rows: List<TestRow>) {
        for (r in rows) {
            ctx.seedCatalogPoi(
                sourceId = r.sourceId,
                name = r.name,
                lon = 0.0,
                lat = 0.0,
                poiType = r.category,
                source = r.source,
                providerRefJson = r.providerRefJson,
                agency = r.agency,
                geomGeoJson = r.geomGeoJson,
            )
        }
    }
}
