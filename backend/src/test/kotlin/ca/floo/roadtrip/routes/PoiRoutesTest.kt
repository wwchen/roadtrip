package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.registry.PoiRegistry
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
import kotlinx.serialization.json.boolean
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
import java.io.File as IoFile

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PoiRoutesTest {
    private lateinit var pg: PostgreSQLContainer<Nothing>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext
    private val testRegistry: PoiRegistry by lazy {
        // Tests live under backend/, repo root is one level up.
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
    fun `bbox returns matching points only`() =
        testApplication {
            seed(
                listOf(
                    row("inside-1", "Vancouver Park", -123.0, 49.0, "campground"),
                    row("inside-2", "Whistler Camp", -122.95, 50.1, "campground"),
                    row("outside-fl", "Miami Park", -80.0, 25.0, "campground"),
                ),
            )
            application { routing { poiRoutes(ctx, testRegistry) } }

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
            // Slim /api/pois ships id + lng/lat + category. Names live behind
            // GET /api/pois/{id}; assert on coordinates here instead.
            val coords =
                features
                    .map {
                        val c = it.jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
                        c[0].jsonPrimitive.content.toDouble() to c[1].jsonPrimitive.content.toDouble()
                    }.toSet()
            assertEquals(setOf(-123.0 to 49.0, -122.95 to 50.1), coords)
        }

    @Test
    fun `bbox over empty water returns empty FeatureCollection with truncated false`() =
        testApplication {
            // Mid-Pacific envelope, far from anything seeded. Must come back as a
            // valid empty FeatureCollection — not an error, not a missing field.
            seed(
                listOf(
                    row("vancouver", "Vancouver Park", -123.0, 49.0, "campground"),
                ),
            )
            application { routing { poiRoutes(ctx, testRegistry) } }

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
                    row("camp-1", "Camp A", -123.0, 49.0, "campground"),
                    row("park-1", "State Park A", -123.05, 49.05, "state-park"),
                    row("pf-1", "PF Vancouver", -123.1, 49.1, "planet-fitness"),
                ),
            )
            application { routing { poiRoutes(ctx, testRegistry) } }

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
    fun `truncated flag flips and the cap is filled when raw count exceeds it`() =
        testApplication {
            // Seed POI_LIMIT + 5 campground rows spread evenly across the
            // viewport. Round-robin sampling should return exactly POI_LIMIT
            // (the global cap), not undershoot from a per-cell ceiling.
            val n = POI_LIMIT + 5
            val rows =
                (1..n).map { i ->
                    // Spread points across the bbox in a 50x40 lattice so
                    // every spatial bucket has at least one candidate.
                    val col = (i - 1) % 50
                    val rowIdx = (i - 1) / 50
                    row(
                        sourceId = "bulk-$i-${"%04x".format(i)}",
                        name = "Site $i",
                        lon = -125.0 + col * (5.0 / 50),
                        lat = 47.0 + rowIdx * (4.0 / 41),
                        category = "campground",
                    )
                }
            seed(rows)
            application { routing { poiRoutes(ctx, testRegistry) } }

            val resp =
                client.post("/api/pois") {
                    contentType(ContentType.Application.Json)
                    setBody(body("-125,47,-120,51", categories = listOf("campground")))
                }
            val parsed = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(true, parsed["truncated"]!!.jsonPrimitive.boolean)
            assertEquals(POI_LIMIT, parsed["features"]!!.jsonArray.size)
        }

    @Test
    fun `dense cluster fills cap by absorbing budget from empty cells`() =
        testApplication {
            // Pre-fix regression: 99 of the 100 spatial cells are empty
            // (everything in one corner). The old uniform per-cell ceiling
            // capped each populated cell at ceil(POI_LIMIT/100) = 20, so
            // the response returned ~20 rows for thousands of available
            // points. Round-robin sampling lets the dense cell absorb
            // budget the empty cells leave on the table, returning exactly
            // POI_LIMIT.
            val n = POI_LIMIT + 200
            val rows =
                (1..n).map { i ->
                    // All inside the SW-corner cell (10x10 grid → cell
                    // size is 0.5 deg in lng, 0.4 deg in lat for this bbox).
                    row(
                        sourceId = "dense-$i-${"%04x".format(i)}",
                        name = "Site $i",
                        lon = -124.99 + (i % 50) * 0.001,
                        lat = 47.01 + (i / 50) * 0.001,
                        category = "campground",
                    )
                }
            seed(rows)
            application { routing { poiRoutes(ctx, testRegistry) } }

            val resp =
                client.post("/api/pois") {
                    contentType(ContentType.Application.Json)
                    setBody(body("-125,47,-120,51", categories = listOf("campground")))
                }
            val parsed = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(true, parsed["truncated"]!!.jsonPrimitive.boolean)
            assertEquals(POI_LIMIT, parsed["features"]!!.jsonArray.size)
        }

    @Test
    fun `under-cap raw count clears the truncated flag`() =
        testApplication {
            // 100 rows spread across the bbox in a 10x10 lattice — every
            // spatial-sample cell gets exactly one. Allocation comfortably
            // covers the count, and per-cell cap is high enough that all
            // rows pass. truncated=false, all 100 returned.
            val rows = mutableListOf<TestRow>()
            for (rIdx in 0 until 10) {
                for (cIdx in 0 until 10) {
                    rows +=
                        row(
                            sourceId = "lattice-$rIdx-$cIdx",
                            name = "Site $rIdx $cIdx",
                            lon = -125.0 + cIdx * (5.0 / 10) + 0.05,
                            lat = 47.0 + rIdx * (4.0 / 10) + 0.05,
                            category = "campground",
                        )
                }
            }
            seed(rows)
            application { routing { poiRoutes(ctx, testRegistry) } }
            val resp =
                client.post("/api/pois") {
                    contentType(ContentType.Application.Json)
                    setBody(body("-125,47,-120,51", categories = listOf("campground")))
                }
            val parsed = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(false, parsed["truncated"]!!.jsonPrimitive.boolean)
            assertEquals(100, parsed["features"]!!.jsonArray.size)
        }

    @Test
    fun `malformed body returns 400`() =
        testApplication {
            application { routing { poiRoutes(ctx, testRegistry) } }

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
            application { routing { poiRoutes(ctx, testRegistry) } }
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
    fun `accidental poi health route no longer returns ok`() =
        testApplication {
            application { routing { poiRoutes(ctx, testRegistry) } }
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/pois/health").status)
        }

    @Test
    fun `zoom below CG_MIN_ZOOM drops campground from results`() =
        testApplication {
            seed(
                listOf(
                    row("cg-1", "Camp", -123.0, 49.0, "campground"),
                    row("sp-1", "Park", -123.05, 49.05, "state-park"),
                ),
            )
            application { routing { poiRoutes(ctx, testRegistry) } }

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
    fun `poi search matches all query terms across punctuation`() =
        testApplication {
            seed(
                listOf(
                    TestRow(
                        sourceId = "banff-village-1",
                        category = "campground",
                        name = "Tunnel Mountain - Village 1",
                        geomGeoJson = """{"type":"Point","coordinates":[-115.5309,51.1917]}""",
                        region = "AB",
                    ),
                    row("banff-trailer", "Tunnel Mountain Trailer Court", -115.52, 51.18, "campground"),
                ),
            )
            application { routing { poiRoutes(ctx, testRegistry) } }

            val resp = client.get("/api/pois/search?q=tunnel%20mountain%20village&limit=5")
            assertEquals(HttpStatusCode.OK, resp.status)
            val parsed = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val results = parsed["results"]!!.jsonArray
            assertEquals(1, results.size)
            val hit = results[0].jsonObject
            assertEquals("Tunnel Mountain - Village 1", hit["name"]!!.jsonPrimitive.content)
            assertEquals("campground", hit["category"]!!.jsonPrimitive.content)
            assertEquals("AB", hit["region"]!!.jsonPrimitive.content)
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
                        add(row("pf-$i", "PF $i", -123.0 + i * 0.0001, 49.0, "planet-fitness"))
                        add(row("cg-$i", "CG $i", -122.9 + i * 0.0001, 49.0, "campground"))
                        add(row("sp-$i", "SP $i", -122.8 + i * 0.0001, 49.0, "state-park"))
                    }
                }
            seed(rows)
            application { routing { poiRoutes(ctx, testRegistry) } }

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
    fun `polygon geometry collapses to centroid Point in the slim shape`() =
        testApplication {
            // Slim /api/pois ships a Point centroid for every row, even when
            // the source geometry is a Polygon. Polygon rendering for parks
            // (when it comes back) will go through a separate tile/render
            // path; the slim endpoint stays Point-only.
            val ring = "[[[-123.1,49.1],[-122.9,49.1],[-122.9,49.3],[-123.1,49.3],[-123.1,49.1]]]"
            val polygonGeoJson = """{"type":"Polygon","coordinates":$ring}"""
            seed(
                listOf(
                    TestRow(
                        sourceId = "poly-1",
                        category = "state-park",
                        name = "Polygon Park",
                        geomGeoJson = polygonGeoJson,
                        region = "BC",
                        unitName = "Polygon Park",
                    ),
                ),
            )
            application { routing { poiRoutes(ctx, testRegistry) } }

            // state-park isn't in the default category set today (the
            // PAD-US sources are disabled), so explicitly request it.
            val resp =
                client.post("/api/pois") {
                    contentType(ContentType.Application.Json)
                    setBody(body("-125,47,-120,51", categories = listOf("state-park")))
                }
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val feat = body["features"]!!.jsonArray.single().jsonObject
            assertEquals("Point", feat["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            // Centroid of the unit square ring is (-123.0, 49.2).
            val coords = feat["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
            assertEquals(-123.0, coords[0].jsonPrimitive.content.toDouble())
            assertEquals(49.2, coords[1].jsonPrimitive.content.toDouble())
        }

    /** Build a JSON request body for POST /api/pois. */
    private fun body(
        bbox: String, // "west,south,east,north"
        categories: List<String>? = null,
        zoom: Int? = null,
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
        sb.append("}")
        return sb.toString()
    }

    /** Local fixture row. Deliberately small — covers the columns the test asserts on. */
    private data class TestRow(
        val sourceId: String,
        val category: String,
        val name: String,
        val geomGeoJson: String,
        val region: String? = "BC",
        val unitName: String? = null,
        val properties: String = """{"test":true}""",
    )

    private fun row(
        sourceId: String,
        name: String,
        lon: Double,
        lat: Double,
        category: String,
    ): TestRow =
        TestRow(
            sourceId = sourceId,
            category = category,
            name = name,
            geomGeoJson = """{"type":"Point","coordinates":[$lon,$lat]}""",
        )

    private fun seed(rows: List<TestRow>) {
        // Direct SQL insert — bypasses Upsert + the legacy Importer. Keeps
        // the test focused on the serving path. Geometry goes through
        // ST_SetSRID(ST_GeomFromGeoJSON(...), 4326) so the SRID matches the
        // pois.geom column declaration.
        for (r in rows) {
            ctx.execute(
                """
                INSERT INTO pois (
                    source, source_id, category, name, geom,
                    region, unit_name, properties, fetched_at
                ) VALUES (
                    ?, ?, ?, ?,
                    ST_SetSRID(ST_GeomFromGeoJSON(?), 4326),
                    ?, ?, ?::jsonb, '2026-06-01 00:00:00+00'::timestamptz
                )
                """.trimIndent(),
                "test",
                r.sourceId,
                r.category,
                r.name,
                r.geomGeoJson,
                r.region,
                r.unitName,
                r.properties,
            )
        }
    }
}
