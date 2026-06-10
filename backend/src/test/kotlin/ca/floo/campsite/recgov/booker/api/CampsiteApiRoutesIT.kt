package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.auth.TokenManager
import ca.floo.campsite.recgov.booker.db.AlertRepo
import ca.floo.campsite.recgov.booker.db.MatchRepo
import ca.floo.campsite.recgov.booker.db.SettingsRepo
import ca.floo.campsite.recgov.booker.events.CompanionRegistry
import ca.floo.campsite.recgov.booker.events.EventBus
import ca.floo.campsite.recgov.booker.poller.AvailabilityClient
import ca.floo.roadtrip.repo.dsl
import ca.floo.roadtrip.repo.migrate
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CampsiteApiRoutesIT {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var ds: DataSource
    private lateinit var ctx: DSLContext
    private lateinit var alerts: AlertRepo
    private lateinit var matches: MatchRepo

    @BeforeAll
    fun startContainer() {
        val image = DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
        pg =
            PostgreSQLContainer<Nothing>(image).apply {
                withDatabaseName("campsite_routes_test")
                withUsername("test")
                withPassword("test")
            }
        pg.start()
        val cfg =
            HikariConfig().apply {
                jdbcUrl = pg.jdbcUrl
                username = pg.username
                password = pg.password
                maximumPoolSize = 4
            }
        ds = HikariDataSource(cfg)
        migrate(ds)
        ctx = dsl(ds)
        alerts = AlertRepo(ctx)
        matches = MatchRepo(ctx)
    }

    @AfterAll
    fun stopContainer() {
        (ds as HikariDataSource).close()
        pg.stop()
    }

    @BeforeEach
    fun cleanTables() {
        ctx.execute("TRUNCATE matches, alerts RESTART IDENTITY CASCADE")
    }

    @Test
    fun `companion routes heartbeat status and online event use dto json`() =
        testApplication {
            val bus = EventBus()
            val companions = CompanionRegistry(Duration.ofSeconds(30))
            application {
                routing {
                    companionRoutes(companions, bus)
                }
            }

            val badHeartbeat =
                client.post("/api/campsite/companion/heartbeat") {
                    contentType(ContentType.Application.Json)
                    setBody("""{}""")
                }
            assertEquals(HttpStatusCode.BadRequest, badHeartbeat.status)
            assertEquals("missing companion_id", json(badHeartbeat.bodyAsText())["error"]!!.jsonPrimitive.content)

            val heartbeat =
                client.post("/api/campsite/companion/heartbeat") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"companion_id":"companion-A","ignored_by_dto":"compat"}""")
                }
            assertEquals(HttpStatusCode.OK, heartbeat.status)
            assertEquals(true, json(heartbeat.bodyAsText())["ok"]!!.jsonPrimitive.boolean)

            val status = client.get("/api/campsite/companion/status")
            assertEquals(HttpStatusCode.OK, status.status)
            val companion =
                json(status.bodyAsText())["companions"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("companion-A", companion["id"]!!.jsonPrimitive.content)
            assertTrue(companion["lastSeen"]!!.jsonPrimitive.content.isNotBlank())
            assertEquals(false, companion["offline"]!!.jsonPrimitive.boolean)

            companions.sweepOffline(Instant.now().plusSeconds(60))
            val recovered =
                client.post("/api/campsite/companion/heartbeat") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"companion_id":"companion-A"}""")
                }
            assertEquals(HttpStatusCode.OK, recovered.status)
            assertEquals(listOf("companion_online"), bus.replayBuffer().map { it.type })
        }

    @Test
    fun `alert routes create list patch and delete with dto json`() =
        testApplication {
            application {
                routing {
                    alertRoutes(alerts, poller = null)
                }
            }

            val badCreate =
                client.post("/api/campsite/alerts") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"campground_id":"232447"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, badCreate.status)
            assertEquals(
                "campground_id, campground_name, start_date, end_date are required",
                json(badCreate.bodyAsText())["error"]!!.jsonPrimitive.content,
            )

            val created =
                client.post("/api/campsite/alerts") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "campground_id":"232447",
                          "campground_name":"Upper Pines",
                          "parent_name":"Yosemite National Park",
                          "parent_id":"2991",
                          "start_date":"2026-07-01",
                          "end_date":"2026-07-04",
                          "min_nights":2,
                          "campsite_types":["STANDARD NONELECTRIC"],
                          "equipment_types":["Tent"],
                          "max_people":4,
                          "specific_sites":["001","012"],
                          "notify_slack":false,
                          "auto_cart":true,
                          "stop_after_match":true,
                          "notes":"main map",
                          "ignored_by_dto":"compat"
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, created.status)
            val alertId = json(created.bodyAsText())["id"]!!.jsonPrimitive.content.toLong()

            val list = client.get("/api/campsite/alerts")
            assertEquals(HttpStatusCode.OK, list.status)
            val item =
                Json
                    .parseToJsonElement(list.bodyAsText())
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(alertId.toString(), item["id"]!!.jsonPrimitive.content)
            assertEquals("232447", item["campground_id"]!!.jsonPrimitive.content)
            assertEquals("Upper Pines", item["campground_name"]!!.jsonPrimitive.content)
            assertEquals("Yosemite National Park", item["parent_name"]!!.jsonPrimitive.content)
            assertEquals("2991", item["parent_id"]!!.jsonPrimitive.content)
            assertEquals("2026-07-01", item["start_date"]!!.jsonPrimitive.content)
            assertEquals("2026-07-04", item["end_date"]!!.jsonPrimitive.content)
            assertEquals("2", item["min_nights"]!!.jsonPrimitive.content)
            assertEquals("4", item["max_people"]!!.jsonPrimitive.content)
            assertEquals(
                "001",
                item["specific_sites"]!!
                    .jsonArray
                    .first()
                    .jsonPrimitive
                    .content,
            )
            assertEquals(false, item["notify_slack"]!!.jsonPrimitive.boolean)
            assertEquals(true, item["auto_cart"]!!.jsonPrimitive.boolean)
            assertEquals(true, item["stop_after_match"]!!.jsonPrimitive.boolean)
            assertEquals("active", item["status"]!!.jsonPrimitive.content)
            assertEquals("main map", item["notes"]!!.jsonPrimitive.content)

            val invalidPatch =
                client.patch("/api/campsite/alerts/$alertId") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"status":"bogus"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, invalidPatch.status)
            assertEquals(
                "status must be active, paused, or done",
                json(invalidPatch.bodyAsText())["error"]!!.jsonPrimitive.content,
            )

            val patched =
                client.patch("/api/campsite/alerts/$alertId") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"status":"paused","specific_sites":[]}""")
                }
            assertEquals(HttpStatusCode.OK, patched.status)
            assertEquals(true, json(patched.bodyAsText())["ok"]!!.jsonPrimitive.boolean)
            assertEquals("paused", alerts.get(alertId)!!.status)
            assertTrue(alerts.get(alertId)!!.specificSites.isEmpty())

            val deleted = client.delete("/api/campsite/alerts/$alertId")
            assertEquals(HttpStatusCode.OK, deleted.status)
            assertEquals(true, json(deleted.bodyAsText())["ok"]!!.jsonPrimitive.boolean)
            assertNull(alerts.get(alertId))
        }

    @Test
    fun `debug synth creates match exposed by list get and availability routes`() =
        testApplication {
            val bus = EventBus()
            application {
                routing {
                    campsiteDebugRoutes(alerts, matches, bus)
                    matchRoutes(matches, availabilityClient())
                }
            }

            val created =
                client.post("/api/admin/campsite/debug/synth-match") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "campgroundId":"232447",
                          "campsiteId":"0",
                          "startDate":"2026-08-01",
                          "endDate":"2026-08-02",
                          "ignoredByDto":"compat"
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, created.status)
            val createdJson = json(created.bodyAsText())
            val matchId = createdJson["id"]!!.jsonPrimitive.content.toLong()

            val list = client.get("/api/campsite/matches?limit=30")
            assertEquals(HttpStatusCode.OK, list.status)
            val first =
                Json
                    .parseToJsonElement(list.bodyAsText())
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("spike-232447", first["campground_name"]!!.jsonPrimitive.content)
            assertEquals("spike-site", first["campsite_site"]!!.jsonPrimitive.content)

            val one = client.get("/api/campsite/matches/$matchId")
            assertEquals(HttpStatusCode.OK, one.status)
            val oneJson = json(one.bodyAsText())
            assertEquals("232447", oneJson["campgroundId"]!!.jsonPrimitive.content)
            assertEquals("spike-232447", oneJson["campgroundName"]!!.jsonPrimitive.content)

            val availability = client.get("/api/campsite/matches/$matchId/availability")
            assertEquals(HttpStatusCode.OK, availability.status)
            assertEquals("unqueryable", json(availability.bodyAsText())["status"]!!.jsonPrimitive.content)

            assertEquals(listOf("match"), bus.replayBuffer().map { it.type })
        }

    @Test
    fun `companion work routes claim result and mark stop-after-match alert done`() =
        testApplication {
            val bus = EventBus()
            val alertId = seedAlert(autoCart = true, stopAfterMatch = true)
            val matchId = seedMatch(alertId, campsiteId = "100")
            application {
                routing {
                    companionWorkRoutes(alerts, matches, bus, Duration.ofMinutes(5))
                }
            }

            val next = client.get("/api/campsite/companion/work/next")
            assertEquals(HttpStatusCode.OK, next.status)
            assertEquals(
                matchId,
                json(next.bodyAsText())["match"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive
                    .content
                    .toLong(),
            )

            val claimed =
                client.post("/api/campsite/companion/matches/$matchId/claim") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"companion_id":"companion-A"}""")
                }
            assertEquals(HttpStatusCode.OK, claimed.status)
            assertTrue(json(claimed.bodyAsText())["leaseExpires"]!!.jsonPrimitive.content.isNotBlank())

            val duplicate =
                client.post("/api/campsite/companion/matches/$matchId/claim") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"companion_id":"companion-B"}""")
                }
            assertEquals(HttpStatusCode.Conflict, duplicate.status)
            assertEquals("already_claimed_or_done", json(duplicate.bodyAsText())["reason"]!!.jsonPrimitive.content)

            val result =
                client.post("/api/campsite/companion/matches/$matchId/result") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"cart_added":true}""")
                }
            assertEquals(HttpStatusCode.OK, result.status)
            assertEquals(true, json(result.bodyAsText())["ok"]!!.jsonPrimitive.boolean)
            assertEquals("done", alerts.get(alertId)!!.status)
            assertEquals(listOf("claimed", "result"), bus.replayBuffer().map { it.type })
        }

    @Test
    fun `booking cart routes open campground url queue retries and reject invalid extend token`() =
        testApplication {
            val bus = EventBus()
            val settings = FakeSettings()
            val tokenManager = tokenManager(settings)
            val alertId = seedAlert(autoCart = true)
            val matchId = seedMatch(alertId, campsiteId = "0")
            matches.claim(matchId, "companion-A", Duration.ofMinutes(5))
            matches.result(matchId, cartAdded = false)

            application {
                routing {
                    bookingCartRoutes(matches, bus, settings, tokenManager)
                }
            }

            val open =
                client.post("/api/campsite/booking/matches/$matchId/cart") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"action":"open"}""")
                }
            assertEquals(HttpStatusCode.OK, open.status)
            assertTrue(json(open.bodyAsText())["url"]!!.jsonPrimitive.content.contains("/campgrounds/232447"))

            val queued =
                client.post("/api/campsite/booking/matches/$matchId/cart") {
                    contentType(ContentType.Application.Json)
                    setBody("""{}""")
                }
            assertEquals(HttpStatusCode.OK, queued.status)
            val queuedJson = json(queued.bodyAsText())
            assertEquals(true, queuedJson["queued"]!!.jsonPrimitive.boolean)
            assertEquals(false, queuedJson["cart_added"]!!.jsonPrimitive.boolean)
            assertNull(matches.get(matchId)!!.claimedBy)
            assertEquals(listOf("match"), bus.replayBuffer().map { it.type })

            val missingToken = client.post("/api/campsite/booking/cart/extend")
            assertEquals(HttpStatusCode.BadRequest, missingToken.status)

            settings.set("recgov_token", fakeJwt(expSecondsFromNow = -60))
            val expired = client.post("/api/campsite/booking/cart/extend")
            assertEquals(HttpStatusCode.BadRequest, expired.status)
            assertEquals("recgov token expired", json(expired.bodyAsText())["error"]!!.jsonPrimitive.content)
        }

    private fun seedAlert(
        autoCart: Boolean,
        stopAfterMatch: Boolean = false,
    ): Long =
        alerts.create(
            AlertRepo.CreateInput(
                campgroundId = "232447",
                campgroundName = "Upper Pines",
                parentName = null,
                parentId = null,
                startDate = "2026-07-01",
                endDate = "2026-07-05",
                minNights = 1,
                campsiteTypes = emptyList(),
                equipmentTypes = emptyList(),
                maxPeople = null,
                specificSites = emptyList(),
                notifySlack = false,
                autoCart = autoCart,
                stopAfterMatch = stopAfterMatch,
                notes = null,
            ),
        )

    private fun seedMatch(
        alertId: Long,
        campsiteId: String,
    ): Long =
        matches.create(
            MatchRepo.CreateInput(
                alertId = alertId,
                campgroundId = "232447",
                campsiteId = campsiteId,
                site = "site-$campsiteId",
                loop = "Loop A",
                campsiteType = "STANDARD",
                availableDates = listOf("2026-07-01"),
                firstDate = "2026-07-01",
                nights = 1,
            ),
        )!!

    private fun availabilityClient(): AvailabilityClient {
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        content = """{"campsites":{}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            )
        return AvailabilityClient(client, minGapMs = 0)
    }

    private class FakeSettings(
        initial: Map<String, String> = emptyMap(),
    ) : SettingsRepo(
            org.jooq.impl.DSL
                .using(org.jooq.SQLDialect.POSTGRES),
        ) {
        private val store = ConcurrentHashMap(initial)

        override fun get(key: String): String? = store[key]

        override fun all(): Map<String, String> = store.toMap()

        override fun set(
            key: String,
            value: String,
        ) {
            store[key] = value
        }

        override fun setMany(updates: Map<String, String>) {
            store.putAll(updates)
        }
    }

    private fun tokenManager(settings: FakeSettings): TokenManager =
        TokenManager(
            settings,
            EventBus(),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

    private fun fakeJwt(expSecondsFromNow: Long): String {
        val exp = System.currentTimeMillis() / 1000 + expSecondsFromNow
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
        val body =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("""{"exp":$exp}""".toByteArray())
        return "$header.$body.sig"
    }

    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject
}
