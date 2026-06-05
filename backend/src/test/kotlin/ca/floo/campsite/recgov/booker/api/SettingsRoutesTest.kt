package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.db.SettingsRepo
import ca.floo.campsite.recgov.booker.notifier.SlackNotifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * SettingsRoutes is the surface the onboarding wizard hits — sentinel masking
 * on GET, candidate creds on test-slack, cookie-paste extraction on
 * test-cookies, and clear-session. These tests stub SettingsRepo + the Slack
 * client so we don't need a Postgres container or a real Slack workspace.
 *
 * The TokenManager-dependent paths (refresh-token, test-chrome with expired
 * token) are not covered here on purpose; they're better tested where
 * TokenManager itself lives.
 */
class SettingsRoutesTest {
    private val mask = "••••••••"

    /** In-memory SettingsRepo subclass — production code uses jOOQ + Postgres. */
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

    /**
     * MockEngine-backed Slack client that records every chat.postMessage body
     * and replies with a configurable ok flag.
     */
    private class FakeSlack(
        respondOk: Boolean = true,
    ) {
        val captured = mutableListOf<String>()
        val client: HttpClient =
            HttpClient(
                MockEngine { request ->
                    val body = (request.body as? io.ktor.http.content.TextContent)?.text ?: ""
                    captured += body
                    respond(
                        content =
                            if (respondOk) {
                                """{"ok":"true"}"""
                            } else {
                                """{"ok":"false","error":"invalid_auth"}"""
                            },
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            )
    }

    private fun fakeJwt(expSecondsFromNow: Long): String {
        val exp = System.currentTimeMillis() / 1000 + expSecondsFromNow
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
        val body =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("""{"exp":$exp,"acct":{"account_id":"acct-1","email":"a@b.c"}}""".toByteArray())
        return "$header.$body.sig"
    }

    @Test
    fun `GET masks recgov_token, slack_token, cookies but echoes plain settings`() =
        testApplication {
            val settings =
                FakeSettings(
                    mapOf(
                        "poll_interval" to "60",
                        "slack_token" to "xoxb-real",
                        "slack_channel" to "#camp",
                        "recgov_token" to fakeJwt(3600),
                        "recgov_cookies" to "raw curl trace",
                        "recgov_refresh_creds" to """{"account_id":"a","refresh_id":"r"}""",
                    ),
                )
            val slack = FakeSlack()
            application {
                routing { settingsRoutes(settings, SlackNotifier(settings, slack.client)) }
            }
            val resp = client.get("/api/campsite/settings")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject

            // Plain settings round-trip.
            assertEquals("60", body["poll_interval"]?.jsonPrimitive?.content)
            assertEquals("#camp", body["slack_channel"]?.jsonPrimitive?.content)

            // Sensitive keys masked when set.
            assertEquals(mask, body["slack_token"]?.jsonPrimitive?.content)
            assertEquals(mask, body["recgov_token"]?.jsonPrimitive?.content)
            assertEquals(mask, body["recgov_cookies"]?.jsonPrimitive?.content)
            assertEquals(mask, body["recgov_refresh_creds"]?.jsonPrimitive?.content)

            // Token expiry surfaced for the wizard countdown.
            assertEquals(false, body["recgov_token_expired"]?.jsonPrimitive?.boolean)
        }

    @Test
    fun `GET reports recgov_token_expired=true for an expired JWT`() =
        testApplication {
            val settings = FakeSettings(mapOf("recgov_token" to fakeJwt(-3600)))
            application {
                routing { settingsRoutes(settings, SlackNotifier(settings, FakeSlack().client)) }
            }
            val resp = client.get("/api/campsite/settings")
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(true, body["recgov_token_expired"]?.jsonPrimitive?.boolean)
        }

    @Test
    fun `GET leaves unset sensitive keys as empty strings, not the mask`() =
        testApplication {
            val settings = FakeSettings(mapOf("slack_token" to "", "recgov_token" to ""))
            application {
                routing { settingsRoutes(settings, SlackNotifier(settings, FakeSlack().client)) }
            }
            val resp = client.get("/api/campsite/settings")
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("", body["slack_token"]?.jsonPrimitive?.content)
            assertEquals("", body["recgov_token"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST persists allowed keys and ignores the masked sentinel`() =
        testApplication {
            val settings = FakeSettings(mapOf("slack_token" to "xoxb-existing", "slack_channel" to "#old"))
            application {
                routing { settingsRoutes(settings, SlackNotifier(settings, FakeSlack().client)) }
            }
            val resp =
                client.post("/api/campsite/settings") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"slack_token":"$mask","slack_channel":"#new","poll_interval":"30","not_allowed":"x"}""",
                    )
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            // Channel updated, token preserved (sentinel = "leave unchanged"),
            // unknown key dropped.
            assertEquals("#new", settings.get("slack_channel"))
            assertEquals("xoxb-existing", settings.get("slack_token"))
            assertEquals("30", settings.get("poll_interval"))
            assertTrue(settings.get("not_allowed").isNullOrEmpty())
        }

    @Test
    fun `POST recgov_cookies extracts bearer token from a cURL paste`() =
        testApplication {
            val settings = FakeSettings()
            application {
                routing { settingsRoutes(settings, SlackNotifier(settings, FakeSlack().client)) }
            }
            val token = fakeJwt(3600)
            val curl = """curl 'https://x' -H 'Authorization: Bearer $token' -H 'Cookie: a=1; b=2'"""
            val resp =
                client.post("/api/campsite/settings") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJson("recgov_cookies", curl))
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(token, settings.get("recgov_token"))
        }

    @Test
    fun `test-cookies happy path returns loggedIn + token expiry`() =
        testApplication {
            val settings = FakeSettings()
            application {
                routing { settingsRoutes(settings, SlackNotifier(settings, FakeSlack().client)) }
            }
            val token = fakeJwt(3600)
            val curl = """curl 'https://x' -H 'Authorization: Bearer $token' -H 'Cookie: a=1; b=2'"""
            val resp =
                client.post("/api/campsite/settings/test-cookies") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJson("raw", curl))
                }
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(true, body["loggedIn"]?.jsonPrimitive?.boolean)
            assertEquals(true, body["hasBearer"]?.jsonPrimitive?.boolean)
            assertEquals(false, body["tokenExpired"]?.jsonPrimitive?.boolean)
            // The bearer was persisted so subsequent paths can use it.
            assertEquals(token, settings.get("recgov_token"))
        }

    @Test
    fun `test-cookies on empty input returns loggedIn=false without writing settings`() =
        testApplication {
            val settings = FakeSettings()
            application {
                routing { settingsRoutes(settings, SlackNotifier(settings, FakeSlack().client)) }
            }
            val resp =
                client.post("/api/campsite/settings/test-cookies") {
                    contentType(ContentType.Application.Json)
                    setBody("""{}""")
                }
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(false, body["loggedIn"]?.jsonPrimitive?.boolean)
            assertEquals(0, body["count"]?.jsonPrimitive?.content?.toInt())
            assertTrue(settings.get("recgov_token").isNullOrEmpty())
        }

    @Test
    fun `test-slack hits Slack with saved creds and returns ok on success`() =
        testApplication {
            val settings = FakeSettings(mapOf("slack_token" to "xoxb-saved", "slack_channel" to "#saved"))
            val slack = FakeSlack(respondOk = true)
            application {
                routing { settingsRoutes(settings, SlackNotifier(settings, slack.client)) }
            }
            val resp = client.post("/api/campsite/settings/test-slack")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(1, slack.captured.size)
            assertTrue(slack.captured[0].contains("\"channel\":\"#saved\""))
        }

    @Test
    fun `test-slack returns 500 when Slack reports failure`() =
        testApplication {
            val settings = FakeSettings(mapOf("slack_token" to "xoxb-saved", "slack_channel" to "#saved"))
            val slack = FakeSlack(respondOk = false)
            application {
                routing { settingsRoutes(settings, SlackNotifier(settings, slack.client)) }
            }
            val resp = client.post("/api/campsite/settings/test-slack")
            assertEquals(HttpStatusCode.InternalServerError, resp.status)
            // Even on failure the saved creds must not be clobbered.
            assertEquals("xoxb-saved", settings.get("slack_token"))
        }

    @Test
    fun `test-slack returns 500 when no slack_token is saved`() =
        testApplication {
            val settings = FakeSettings(mapOf("slack_channel" to "#x"))
            application {
                routing { settingsRoutes(settings, SlackNotifier(settings, FakeSlack().client)) }
            }
            val resp = client.post("/api/campsite/settings/test-slack")
            assertEquals(HttpStatusCode.InternalServerError, resp.status)
        }

    @Test
    fun `clear-session wipes recgov keys`() =
        testApplication {
            val settings =
                FakeSettings(
                    mapOf(
                        "recgov_token" to "tok",
                        "recgov_refresh_creds" to "creds",
                        "recgov_cookies" to "raw",
                        "slack_token" to "xoxb",
                    ),
                )
            application {
                routing { settingsRoutes(settings, SlackNotifier(settings, FakeSlack().client)) }
            }
            val resp = client.post("/api/campsite/settings/clear-session")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals("", settings.get("recgov_token"))
            assertEquals("", settings.get("recgov_refresh_creds"))
            assertEquals("", settings.get("recgov_cookies"))
            // Slack creds are *not* part of clear-session — they survive.
            assertNotEquals("", settings.get("slack_token"))
        }

    @Test
    fun `test-chrome without a saved token returns loggedIn=false`() =
        testApplication {
            val settings = FakeSettings()
            application {
                routing { settingsRoutes(settings, SlackNotifier(settings, FakeSlack().client)) }
            }
            val resp = client.post("/api/campsite/settings/test-chrome")
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(false, body["loggedIn"]?.jsonPrimitive?.boolean)
        }

    @Test
    fun `test-chrome with a fresh token returns loggedIn=true without touching tokenManager`() =
        testApplication {
            // tokenManager is null — handler must not reach the refresh path.
            val settings = FakeSettings(mapOf("recgov_token" to fakeJwt(3600)))
            application {
                routing { settingsRoutes(settings, SlackNotifier(settings, FakeSlack().client)) }
            }
            val resp = client.post("/api/campsite/settings/test-chrome")
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(true, body["loggedIn"]?.jsonPrimitive?.boolean)
            assertEquals(false, body["tokenExpired"]?.jsonPrimitive?.boolean)
        }

    @Test
    fun `refresh-token returns 500 when no token manager is wired`() =
        testApplication {
            val settings = FakeSettings()
            application {
                routing { settingsRoutes(settings, SlackNotifier(settings, FakeSlack().client)) }
            }
            val resp = client.post("/api/campsite/settings/refresh-token")
            assertEquals(HttpStatusCode.InternalServerError, resp.status)
        }

    private fun buildJson(
        key: String,
        value: String,
    ): String {
        val escaped =
            value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
        return """{"$key":"$escaped"}"""
    }
}
