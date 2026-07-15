package ca.floo.roadtrip.routes

import ca.floo.roadtrip.config.DispatchConfig
import ca.floo.roadtrip.service.availability.DispatchService
import ca.floo.roadtrip.service.availability.DispatchTestEventService
import ca.floo.roadtrip.service.availability.DispatchWaiterRegistry
import ca.floo.roadtrip.service.availability.DispatchWatchCompletion
import ca.floo.roadtrip.service.availability.InMemoryDispatchStore
import ca.floo.roadtrip.service.notification.SlackNotificationServiceImpl
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private const val TEST_VENDOR = "recgov"
private const val TEST_SIMULATE_RESULT = "success"
private const val TEST_KIND = "test"
private const val TEST_KIND_ATC = "atc"
private const val TEST_PAYLOAD_VERSION = "test.recgov.v1"
private const val TEST_CLAIM_WAIT_SECONDS = 0L
private const val TEST_COMPANION_TOKEN = "companion-token"

private val testClock = Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC)

class DispatchRoutesTest {
    @Test
    fun `test endpoint queues an event that companion can claim and complete`() =
        testApplication {
            application {
                routing {
                    val dispatches = testDispatchService()
                    dispatchRoutes(dispatches, testDispatchEventService(dispatches), testDispatchConfig())
                }
            }

            val queued =
                client.post("/api/dispatches/test") {
                    dispatchAuth()
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "vendor": "$TEST_VENDOR",
                          "simulate_result": "$TEST_SIMULATE_RESULT",
                          "payload": {"request_id": "manual-test"}
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.Created, queued.status)
            val queuedBody = Json.parseToJsonElement(queued.bodyAsText()).jsonObject["dispatch"]!!.jsonObject
            assertEquals(TEST_PAYLOAD_VERSION, queuedBody["payload_version"]!!.jsonPrimitive.content)

            val claimed =
                client.post("/api/dispatches/claim") {
                    dispatchAuth()
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "kinds": ["$TEST_KIND_ATC", "$TEST_KIND"],
                          "vendors": ["$TEST_VENDOR"],
                          "wait_sec": $TEST_CLAIM_WAIT_SECONDS
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, claimed.status)
            val dispatch = Json.parseToJsonElement(claimed.bodyAsText()).jsonObject["dispatch"]!!.jsonObject
            assertEquals(TEST_KIND, dispatch["kind"]!!.jsonPrimitive.content)
            assertEquals(TEST_VENDOR, dispatch["vendor"]!!.jsonPrimitive.content)
            val payload = dispatch["payload"]!!.jsonObject
            assertEquals(TEST_SIMULATE_RESULT, payload["simulate_result"]!!.jsonPrimitive.content)
            assertEquals(TEST_VENDOR, payload["vendor"]!!.jsonPrimitive.content)
            assertEquals(TEST_PAYLOAD_VERSION, payload["payload_version"]!!.jsonPrimitive.content)
            assertEquals("manual-test", payload["request_id"]!!.jsonPrimitive.content)
            val dispatchId = dispatch["id"]!!.jsonPrimitive.content
            val leaseToken = dispatch["lease_token"]!!.jsonPrimitive.content
            assertNotNull(leaseToken)

            val completed =
                client.post("/api/dispatches/$dispatchId/complete") {
                    dispatchAuth()
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "lease_token": "$leaseToken",
                          "result": {"simulated": true}
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, completed.status)
            val completedBody = Json.parseToJsonElement(completed.bodyAsText()).jsonObject
            assertEquals("completed", completedBody["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `claim returns no content when there is no matching vendor dispatch`() =
        testApplication {
            application {
                routing {
                    val dispatches = testDispatchService()
                    dispatchRoutes(dispatches, testDispatchEventService(dispatches), testDispatchConfig())
                }
            }

            val resp =
                client.post("/api/dispatches/claim") {
                    dispatchAuth()
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "kind": "$TEST_KIND",
                          "vendors": ["aspira"],
                          "wait_sec": $TEST_CLAIM_WAIT_SECONDS
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.NoContent, resp.status)
        }

    @Test
    fun `dispatch endpoints reject requests without companion token`() =
        testApplication {
            application {
                routing {
                    val dispatches = testDispatchService()
                    dispatchRoutes(dispatches, testDispatchEventService(dispatches), testDispatchConfig())
                }
            }

            val resp =
                client.post("/api/dispatches/claim") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "kind": "$TEST_KIND",
                          "vendors": ["$TEST_VENDOR"],
                          "wait_sec": $TEST_CLAIM_WAIT_SECONDS
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    @Test
    fun `test endpoint is disabled unless explicitly enabled`() =
        testApplication {
            application {
                routing {
                    val dispatches = testDispatchService()
                    dispatchRoutes(
                        dispatches,
                        testDispatchEventService(dispatches),
                        testDispatchConfig(testEndpointEnabled = false),
                    )
                }
            }

            val resp =
                client.post("/api/dispatches/test") {
                    dispatchAuth()
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "vendor": "$TEST_VENDOR",
                          "simulate_result": "$TEST_SIMULATE_RESULT"
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.Forbidden, resp.status)
        }

    @Test
    fun `test endpoint only queues test dispatches`() =
        testApplication {
            application {
                routing {
                    val dispatches = testDispatchService()
                    dispatchRoutes(dispatches, testDispatchEventService(dispatches), testDispatchConfig())
                }
            }

            val resp =
                client.post("/api/dispatches/test") {
                    dispatchAuth()
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "kind": "$TEST_KIND_ATC",
                          "vendor": "$TEST_VENDOR",
                          "simulate_result": "$TEST_SIMULATE_RESULT"
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }

    private fun testDispatchService(): DispatchService =
        DispatchService(
            store = InMemoryDispatchStore(),
            waiters = DispatchWaiterRegistry(),
            slack = SlackNotificationServiceImpl(config = null),
            watchCompletion = DispatchWatchCompletion { true },
            clock = testClock,
        )

    private fun testDispatchEventService(dispatches: DispatchService): DispatchTestEventService = DispatchTestEventService(dispatches)

    private fun testDispatchConfig(
        testEndpointEnabled: Boolean = true,
        companionToken: String? = TEST_COMPANION_TOKEN,
    ): DispatchConfig = DispatchConfig(companionToken = companionToken, testEndpointEnabled = testEndpointEnabled)

    private fun io.ktor.client.request.HttpRequestBuilder.dispatchAuth() {
        header(HttpHeaders.Authorization, "Bearer $TEST_COMPANION_TOKEN")
    }
}
