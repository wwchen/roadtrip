package ca.floo.roadtrip.routes

import ca.floo.roadtrip.service.availability.DispatchService
import ca.floo.roadtrip.service.availability.DispatchWaiterRegistry
import ca.floo.roadtrip.service.availability.DispatchWatchCompletion
import ca.floo.roadtrip.service.availability.InMemoryDispatchStore
import ca.floo.roadtrip.service.notification.SlackNotificationServiceImpl
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
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private const val TEST_VENDOR = "recgov"
private const val TEST_SIMULATE_RESULT = "success"
private const val TEST_KIND = "test"
private const val TEST_CLAIM_WAIT_SECONDS = 0L

class DispatchRoutesTest {
    @Test
    fun `test endpoint queues an event that companion can claim and complete`() =
        testApplication {
            application {
                routing {
                    dispatchRoutes(testDispatchService())
                }
            }

            val queued =
                client.post("/api/dispatches/test") {
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
            assertEquals(HttpStatusCode.Created, queued.status)
            val queuedBody = Json.parseToJsonElement(queued.bodyAsText()).jsonObject["dispatch"]!!.jsonObject
            assertEquals("atc.recgov.v1", queuedBody["payload_version"]!!.jsonPrimitive.content)

            val claimed =
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
            assertEquals(HttpStatusCode.OK, claimed.status)
            val dispatch = Json.parseToJsonElement(claimed.bodyAsText()).jsonObject["dispatch"]!!.jsonObject
            assertEquals(TEST_KIND, dispatch["kind"]!!.jsonPrimitive.content)
            assertEquals(TEST_VENDOR, dispatch["vendor"]!!.jsonPrimitive.content)
            val payload = dispatch["payload"]!!.jsonObject
            assertEquals(TEST_SIMULATE_RESULT, payload["simulate_result"]!!.jsonPrimitive.content)
            assertEquals(TEST_VENDOR, payload["vendor"]!!.jsonPrimitive.content)
            assertEquals("atc.recgov.v1", payload["payload_version"]!!.jsonPrimitive.content)
            assertEquals("2026-07-14", payload["start_date"]!!.jsonPrimitive.content)
            assertEquals("2026-07-15", payload["end_date"]!!.jsonPrimitive.content)
            assertEquals(1, payload["openings"]!!.jsonArray.size)
            val dispatchId = dispatch["id"]!!.jsonPrimitive.content
            val leaseToken = dispatch["lease_token"]!!.jsonPrimitive.content
            assertNotNull(leaseToken)

            val completed =
                client.post("/api/dispatches/$dispatchId/complete") {
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
                    dispatchRoutes(testDispatchService())
                }
            }

            val resp =
                client.post("/api/dispatches/claim") {
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

    private fun testDispatchService(): DispatchService =
        DispatchService(
            store = InMemoryDispatchStore(),
            waiters = DispatchWaiterRegistry(),
            slack = SlackNotificationServiceImpl(config = null),
            watchCompletion = DispatchWatchCompletion { true },
            clock = Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC),
        )
}
