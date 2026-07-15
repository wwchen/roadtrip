package ca.floo.roadtrip.routes

import ca.floo.roadtrip.config.DispatchConfig
import ca.floo.roadtrip.models.api.RecGovAccountSchema
import ca.floo.roadtrip.models.api.RecGovRecaccountSchema
import ca.floo.roadtrip.models.api.RecGovSessionImportRequest
import ca.floo.roadtrip.service.booking.adapters.recgov.RecGovBookingSessionStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

private const val TEST_COMPANION_TOKEN = "companion-token"
private const val TEST_ACCESS_TOKEN = "jwt-1"
private const val TEST_IMPORTED_ACCESS_TOKEN = "jwt-2"
private const val TEST_ACCOUNT_ID = "acct-1"
private const val FRESH_TOKEN_PATH = "/api/campsite/booking/session/fresh-token"
private const val IMPORT_TOKEN_PATH = "/api/campsite/booking/session/import"
private const val TEST_PENDING_TTL_SECONDS = 30L
private const val TEST_MAX_CLAIM_WAIT_SECONDS = 30L
private const val TEST_MIN_CLAIM_WAIT_MILLIS = 1L
private const val TEST_DEFAULT_LEASE_SECONDS = 30L
private const val TEST_MIN_LEASE_SECONDS = 1L
private const val TEST_MAX_LEASE_SECONDS = 120L

class RecGovBookingSessionRoutesTest {
    @Test
    fun `fresh token route requires companion auth`() =
        testApplication {
            application {
                routing {
                    recGovBookingSessionRoutes(FakeSessionProvider(testRecaccount()), testDispatchConfig())
                }
            }

            val response = client.get(FRESH_TOKEN_PATH)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("unauthorized", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `fresh token route returns not found json when no recaccount is configured`() =
        testApplication {
            application {
                routing {
                    recGovBookingSessionRoutes(FakeSessionProvider(null), testDispatchConfig())
                }
            }

            val response =
                client.get(FRESH_TOKEN_PATH) {
                    companionAuth()
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("no_recgov_recaccount", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `fresh token route returns recaccount json for authenticated companion`() =
        testApplication {
            application {
                routing {
                    recGovBookingSessionRoutes(FakeSessionProvider(testRecaccount()), testDispatchConfig())
                }
            }

            val response =
                client.get(FRESH_TOKEN_PATH) {
                    companionAuth()
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(TEST_ACCESS_TOKEN, body["access_token"]!!.jsonPrimitive.content)
            assertEquals(TEST_ACCOUNT_ID, body["account"]!!.jsonObject["account_id"]!!.jsonPrimitive.content)
        }

    @Test
    fun `import route requires companion auth`() =
        testApplication {
            application {
                routing {
                    recGovBookingSessionRoutes(FakeSessionProvider(null), testDispatchConfig())
                }
            }

            val response =
                client.post(IMPORT_TOKEN_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(importRequestBody(testRecaccount()))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("unauthorized", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `import route rejects invalid recaccount json`() =
        testApplication {
            application {
                routing {
                    recGovBookingSessionRoutes(FakeSessionProvider(null), testDispatchConfig())
                }
            }

            val response =
                client.post(IMPORT_TOKEN_PATH) {
                    companionAuth()
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(RecGovSessionImportRequest(raw = "not-json")))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalid_recgov_recaccount", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `import route stores browser recaccount for future fresh token calls`() =
        testApplication {
            val session = FakeSessionProvider(null)
            application {
                routing {
                    recGovBookingSessionRoutes(session, testDispatchConfig())
                }
            }

            val imported = testRecaccount(TEST_IMPORTED_ACCESS_TOKEN)
            val importResponse =
                client.post(IMPORT_TOKEN_PATH) {
                    companionAuth()
                    contentType(ContentType.Application.Json)
                    setBody(importRequestBody(imported))
                }

            assertEquals(HttpStatusCode.OK, importResponse.status)
            val importBody = Json.parseToJsonElement(importResponse.bodyAsText()).jsonObject
            assertEquals(TEST_IMPORTED_ACCESS_TOKEN, importBody["access_token"]!!.jsonPrimitive.content)

            val freshResponse =
                client.get(FRESH_TOKEN_PATH) {
                    companionAuth()
                }

            assertEquals(HttpStatusCode.OK, freshResponse.status)
            val freshBody = Json.parseToJsonElement(freshResponse.bodyAsText()).jsonObject
            assertEquals(TEST_IMPORTED_ACCESS_TOKEN, freshBody["access_token"]!!.jsonPrimitive.content)
        }

    private class FakeSessionProvider(
        private var recaccount: RecGovRecaccountSchema?,
    ) : RecGovBookingSessionStore {
        override suspend fun freshRecaccount(): RecGovRecaccountSchema? = recaccount

        override suspend fun importRecaccount(raw: String): RecGovRecaccountSchema? =
            runCatching {
                Json.decodeFromString<RecGovRecaccountSchema>(raw)
            }.getOrNull()?.also {
                recaccount = it
            }
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.companionAuth() {
    header(HttpHeaders.Authorization, "Bearer $TEST_COMPANION_TOKEN")
}

private fun testRecaccount(accessToken: String = TEST_ACCESS_TOKEN): RecGovRecaccountSchema =
    RecGovRecaccountSchema(
        accessToken = accessToken,
        expiration = "2026-07-15T21:00:00Z",
        account = RecGovAccountSchema(accountId = TEST_ACCOUNT_ID),
        isGuest = false,
        refreshId = "",
    )

private fun importRequestBody(recaccount: RecGovRecaccountSchema): String =
    Json.encodeToString(
        RecGovSessionImportRequest(
            raw = Json.encodeToString(recaccount),
        ),
    )

private fun testDispatchConfig(): DispatchConfig =
    DispatchConfig(
        pendingTtl = Duration.ofSeconds(TEST_PENDING_TTL_SECONDS),
        maxClaimWait = Duration.ofSeconds(TEST_MAX_CLAIM_WAIT_SECONDS),
        minClaimWait = Duration.ofMillis(TEST_MIN_CLAIM_WAIT_MILLIS),
        defaultLease = Duration.ofSeconds(TEST_DEFAULT_LEASE_SECONDS),
        minLease = Duration.ofSeconds(TEST_MIN_LEASE_SECONDS),
        maxLease = Duration.ofSeconds(TEST_MAX_LEASE_SECONDS),
        companionToken = TEST_COMPANION_TOKEN,
        testEndpointEnabled = true,
    )
