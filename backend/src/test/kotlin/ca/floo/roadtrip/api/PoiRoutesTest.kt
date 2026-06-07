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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PoiRoutesTest {
    private lateinit var pg: PostgreSQLContainer<Nothing>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext

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
    fun `bbox returns matching points only`() =
        testApplication {
            seed(
                listOf(
                    row("inside-1", "Vancouver Park", -123.0, 49.0, Category.CAMPGROUND),
                    row("inside-2", "Whistler Camp", -122.95, 50.1, Category.CAMPGROUND),
                    row("outside-fl", "Miami Park", -80.0, 25.0, Category.CAMPGROUND),
                ),
            )
            application { routing { poiRoutes(ctx) } }

            val resp =
                client.post("/api/pois") {
                    contentType(ContentType.Application.Json)
                    setBody(body("-125,47,-120,51"))
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("FeatureCollection", body["type"]!!.jsonPrimitive.content)
            assertEquals(false, body["truncated"]!!.jsonPrimitive.boolean)
            val features = body["features"]!!.jsonArray
            assertEquals(2, features.size)
            val names =
                features
                    .map {
                        it.jsonObject["properties"]!!
                            .jsonObject["name"]!!
                            .jsonPrimitive.content
                    }.toSet()
            assertEquals(setOf("Vancouver Park", "Whistler Camp"), names)
        }

    @Test
    fun `bbox over empty water returns empty FeatureCollection with truncated false`() =
        testApplication {
            // Mid-Pacific envelope, far from anything seeded. Must come back as a
            // valid empty FeatureCollection — not an error, not a missing field.
            seed(
                listOf(
                    row("vancouver", "Vancouver Park", -123.0, 49.0, Category.CAMPGROUND),
                ),
            )
            application { routing { poiRoutes(ctx) } }

            val resp =
                client.post("/api/pois") {
                    contentType(ContentType.Application.Json)
                    setBody(body("-160,5,-150,15"))
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("FeatureCollection", body["type"]!!.jsonPrimitive.content)
            assertEquals(false, body["truncated"]!!.jsonPrimitive.boolean)
            assertEquals(0, body["features"]!!.jsonArray.size)
        }

    @Test
    fun `category filter narrows the set`() =
        testApplication {
            seed(
                listOf(
                    row("camp-1", "Camp A", -123.0, 49.0, Category.CAMPGROUND),
                    row("park-1", "State Park A", -123.05, 49.05, Category.STATE_PARK),
                    row("pf-1", "PF Vancouver", -123.1, 49.1, Category.PLANET_FITNESS),
                ),
            )
            application { routing { poiRoutes(ctx) } }

            val resp =
                client.post("/api/pois") {
                    contentType(ContentType.Application.Json)
                    setBody(body("-125,47,-120,51", categories = listOf("campground", "state-park")))
                }
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val cats =
                body["features"]!!
                    .jsonArray
                    .map {
                        it.jsonObject["properties"]!!
                            .jsonObject["category"]!!
                            .jsonPrimitive.content
                    }.toSet()
            assertEquals(setOf("campground", "state-park"), cats)
        }

    @Test
    fun `truncation kicks in above the per-category cap`() =
        testApplication {
            // Seed POI_LIMIT + 5 campground rows in a tight bbox. Per-category
            // limit is POI_LIMIT/5 (5 default categories), so we should see
            // exactly that many — and the response is NOT marked truncated
            // because per-cat truncation is the design, not an error state.
            val rows =
                (1..(POI_LIMIT + 5)).map { i ->
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

            // No categories filter → defaults to all 5, each capped at POI_LIMIT/5 = 400.
            val resp =
                client.post("/api/pois") {
                    contentType(ContentType.Application.Json)
                    setBody(body("-125,47,-120,51"))
                }
            val parsed = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(POI_LIMIT / 5, parsed["features"]!!.jsonArray.size)

            // When the caller scopes to a single category, that category gets
            // the full POI_LIMIT slot — and global truncation kicks in only
            // when total rows >= POI_LIMIT + 1.
            val respScoped =
                client.post("/api/pois") {
                    contentType(ContentType.Application.Json)
                    setBody(body("-125,47,-120,51", categories = listOf("campground")))
                }
            val parsedScoped = Json.parseToJsonElement(respScoped.bodyAsText()).jsonObject
            assertEquals(true, parsedScoped["truncated"]!!.jsonPrimitive.boolean)
            assertEquals(POI_LIMIT, parsedScoped["features"]!!.jsonArray.size)
        }

    @Test
    fun `malformed body returns 400`() =
        testApplication {
            application { routing { poiRoutes(ctx) } }

            // Empty body
            assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .post("/api/pois") {
                        contentType(ContentType.Application.Json)
                        setBody("")
                    }.status,
            )
            // Missing bbox
            assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .post("/api/pois") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"zoom":8}""")
                    }.status,
            )
            // bbox not 4 elements
            assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .post("/api/pois") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"bbox":[-125,47,-120]}""")
                    }.status,
            )
            // bbox south >= north
            assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .post("/api/pois") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"bbox":[-125,51,-120,47]}""")
                    }.status,
            )
            // bbox values not numeric
            assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .post("/api/pois") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"bbox":["a","b","c","d"]}""")
                    }.status,
            )
        }

    @Test
    fun `antimeridian-crossing bbox is rejected with 400`() =
        testApplication {
            // west=170, east=-170 (the bbox wraps the antimeridian). PostGIS
            // ST_MakeEnvelope can't express a wrapping envelope without splitting,
            // so we reject at the API layer instead of returning misleading rows.
            application { routing { poiRoutes(ctx) } }
            assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .post("/api/pois") {
                        contentType(ContentType.Application.Json)
                        setBody(body("170,-10,-170,10"))
                    }.status,
            )
        }

    @Test
    fun `health route still serves`() =
        testApplication {
            application { routing { poiRoutes(ctx) } }
            assertEquals(HttpStatusCode.OK, client.get("/api/pois/health").status)
        }

    @Test
    fun `polygon filter excludes points outside the corridor`() =
        testApplication {
            // Three points: two inside a small polygon around Vancouver, one
            // outside in eastern WA. With polygon set, only the two inside
            // should come back.
            seed(
                listOf(
                    row("inside-1", "Inside A", -123.0, 49.05, Category.CAMPGROUND),
                    row("inside-2", "Inside B", -123.05, 49.10, Category.CAMPGROUND),
                    row("outside-1", "Outside East", -118.0, 47.0, Category.CAMPGROUND),
                ),
            )
            application { routing { poiRoutes(ctx) } }

            // Polygon: small square around Vancouver, BC
            val polygon = """{"type":"Polygon","coordinates":[[[-123.2,48.9],[-122.8,48.9],[-122.8,49.2],[-123.2,49.2],[-123.2,48.9]]]}"""
            val resp =
                client.post("/api/pois") {
                    contentType(ContentType.Application.Json)
                    setBody(body("-125,47,-117,51", polygon = polygon))
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val parsed = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val names =
                parsed["features"]!!
                    .jsonArray
                    .map {
                        it.jsonObject["properties"]!!
                            .jsonObject["name"]!!
                            .jsonPrimitive.content
                    }.toSet()
            assertEquals(setOf("Inside A", "Inside B"), names)
            // Response should mark corridor:true so the FE can show different UI.
            assertEquals(true, parsed["corridor"]?.jsonPrimitive?.boolean)
        }

    // Pathological self-intersecting polygons (e.g. turf.buffer of a route
    // that doubles back) can still throw GEOS TopologyException even after
    // ST_MakeValid. The handler in poiRoutes() catches that and falls back
    // to bbox-only so the user never sees a 500. This is hard to reproduce
    // deterministically across PostGIS versions (testcontainers' GEOS
    // handles some bowties that production Docker rejects), so the
    // fallback is verified by manual smoke against the live backend, not
    // here. The polygon-too-large path below is the deterministic guard.

    @Test
    fun `polygon with too many vertices returns 400`() =
        testApplication {
            application { routing { poiRoutes(ctx) } }
            // Build a polygon with 2001 vertices (one over the cap).
            val ring = StringBuilder("[")
            for (i in 0..2000) {
                if (i > 0) ring.append(",")
                ring.append("[${-123.0 + i * 0.0001},49.0]")
            }
            ring.append("]")
            val polygon = """{"type":"Polygon","coordinates":[$ring]}"""
            val resp =
                client.post("/api/pois") {
                    contentType(ContentType.Application.Json)
                    setBody(body("-125,47,-117,51", polygon = polygon))
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }

    @Test
    fun `zoom below CG_MIN_ZOOM drops campground from results`() =
        testApplication {
            seed(
                listOf(
                    row("cg-1", "Camp", -123.0, 49.0, Category.CAMPGROUND),
                    row("sp-1", "Park", -123.05, 49.05, Category.STATE_PARK),
                ),
            )
            application { routing { poiRoutes(ctx) } }

            // Zoom 4 < CG_MIN_ZOOM=6 → campground category dropped server-side
            // even when explicitly requested.
            val resp =
                client.post("/api/pois") {
                    contentType(ContentType.Application.Json)
                    setBody(body("-125,47,-120,51", categories = listOf("campground", "state-park"), zoom = 4))
                }
            val parsed = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val cats =
                parsed["features"]!!
                    .jsonArray
                    .map {
                        it.jsonObject["properties"]!!
                            .jsonObject["category"]!!
                            .jsonPrimitive.content
                    }.toSet()
            assertEquals(setOf("state-park"), cats)
        }

    @Test
    fun `per-category limit gives each category its own slot budget`() =
        testApplication {
            // Seed 50 PF + 50 CG + 50 SP all in tight bbox. With per-cat limit
            // of POI_LIMIT/3 = 666, each category returns all 50 of its rows
            // (no starvation).
            val rows =
                buildList {
                    repeat(50) { i ->
                        add(row("pf-$i", "PF $i", -123.0 + i * 0.0001, 49.0, Category.PLANET_FITNESS))
                        add(row("cg-$i", "CG $i", -122.9 + i * 0.0001, 49.0, Category.CAMPGROUND))
                        add(row("sp-$i", "SP $i", -122.8 + i * 0.0001, 49.0, Category.STATE_PARK))
                    }
                }
            seed(rows)
            application { routing { poiRoutes(ctx) } }

            val resp =
                client.post("/api/pois") {
                    contentType(ContentType.Application.Json)
                    setBody(body("-125,47,-120,51", categories = listOf("planet-fitness", "campground", "state-park")))
                }
            val parsed = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val byCat = mutableMapOf<String, Int>()
            parsed["features"]!!.jsonArray.forEach {
                val c =
                    it.jsonObject["properties"]!!
                        .jsonObject["category"]!!
                        .jsonPrimitive.content
                byCat[c] = (byCat[c] ?: 0) + 1
            }
            assertEquals(50, byCat["planet-fitness"])
            assertEquals(50, byCat["campground"])
            assertEquals(50, byCat["state-park"])
        }

    @Test
    fun `polygon geometry rendered as Polygon GeoJSON`() =
        testApplication {
            // State park with a tiny polygon — result must come back as GeoJSON
            // Polygon, not WKT and not just the bounding point.
            val ring = "[[[-123.1,49.1],[-122.9,49.1],[-122.9,49.3],[-123.1,49.3],[-123.1,49.1]]]"
            val polygonGeoJson = """{"type":"Polygon","coordinates":$ring}"""
            seed(
                listOf(
                    StagedPoi(
                        sourceId = "poly-1",
                        category = Category.STATE_PARK,
                        name = "Polygon Park",
                        geomGeoJson = polygonGeoJson,
                        region = "BC",
                        unitName = "Polygon Park",
                        properties = buildJsonObject { put("test", true) },
                        reserveUrl = null,
                        fetchedAt = Instant.parse("2026-06-01T00:00:00Z"),
                    ),
                ),
            )
            application { routing { poiRoutes(ctx) } }

            val resp =
                client.post("/api/pois") {
                    contentType(ContentType.Application.Json)
                    setBody(body("-125,47,-120,51"))
                }
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val feat = body["features"]!!.jsonArray.single().jsonObject
            assertEquals("Polygon", feat["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }

    /** Build a JSON request body for POST /api/pois. */
    private fun body(
        bbox: String, // "west,south,east,north"
        categories: List<String>? = null,
        zoom: Int? = null,
        polygon: String? = null, // raw GeoJSON Polygon string
    ): String {
        val parts = bbox.split(",")
        val sb = StringBuilder()
        sb.append("""{"bbox":[${parts[0]},${parts[1]},${parts[2]},${parts[3]}]""")
        if (zoom != null) sb.append(""","zoom":$zoom""")
        if (categories != null) {
            sb.append(""","categories":[""")
            categories.forEachIndexed { i, c ->
                if (i > 0) sb.append(",")
                sb.append("\"").append(c).append("\"")
            }
            sb.append("]")
        }
        if (polygon != null) sb.append(""","polygon":$polygon""")
        sb.append("}")
        return sb.toString()
    }

    private fun row(
        sourceId: String,
        name: String,
        lon: Double,
        lat: Double,
        category: Category,
    ): StagedPoi =
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
        Importer(ctx).run(
            object : Source {
                override val name = "test"

                override fun staged() = rows.asSequence()
            },
        )
    }
}
