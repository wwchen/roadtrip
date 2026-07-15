package ca.floo.roadtrip.routes

import ca.floo.roadtrip.config.DispatchConfig
import ca.floo.roadtrip.models.api.RecGovAccountSchema
import ca.floo.roadtrip.models.api.RecGovRecaccountSchema
import ca.floo.roadtrip.service.booking.adapters.recgov.RecGovBookingSessionProvider
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

private const val TEST_COMPANION_TOKEN = "companion-token"
private const val TEST_ACCESS_TOKEN = "jwt-1"
private const val TEST_ACCOUNT_ID = "acct-1"
private const val FRESH_TOKEN_PATH = "/api/campsite/booking/session/fresh-token"
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

    private class FakeSessionProvider(
        private val recaccount: RecGovRecaccountSchema?,
    ) : RecGovBookingSessionProvider {
        override suspend fun freshRecaccount(): RecGovRecaccountSchema? = recaccount
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.companionAuth() {
    header(HttpHeaders.Authorization, "Bearer $TEST_COMPANION_TOKEN")
}

private fun testRecaccount(): RecGovRecaccountSchema =
    RecGovRecaccountSchema(
        accessToken = TEST_ACCESS_TOKEN,
        expiration = "2026-07-15T21:00:00Z",
        account = RecGovAccountSchema(accountId = TEST_ACCOUNT_ID),
        isGuest = false,
        refreshId = "",
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
