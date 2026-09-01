package ca.floo.roadtrip.client.companion

import ca.floo.roadtrip.config.RecGovAtcConfig
import ca.floo.roadtrip.service.booking.RecGovAtcOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpRecGovAtcExecutorTest {
    @Test
    fun `posts payload and maps success response`() =
        runBlocking {
            CompanionTestServer
                .of(responses = mapOf("/atc" to TestResponse(body = """{"ok":true,"cart_added":true}""")))
                .use { server ->
                    val executor = HttpRecGovAtcExecutor(RecGovAtcConfig(server.baseUrl, Duration.ofSeconds(5)))

                    val outcome = executor.addToCart(atcPayload())

                    assertTrue(outcome is RecGovAtcOutcome.Completed)
                    assertEquals(listOf("/atc"), server.paths)
                    assertEquals(atcPayload().toString(), server.bodies.last())
                }
        }

    @Test
    fun `the profile id the caller addressed rides in the posted body`() =
        runBlocking {
            CompanionTestServer
                .of(responses = mapOf("/atc" to TestResponse(body = """{"ok":true,"cart_added":true}""")))
                .use { server ->
                    val executor = HttpRecGovAtcExecutor(RecGovAtcConfig(server.baseUrl, Duration.ofSeconds(5)))

                    executor.addToCart(atcPayload())

                    assertTrue(server.bodies.single().contains(""""profile_id":"91""""))
                }
        }

    @Test
    fun `no health preflight is issued — the adapter owns that check`() =
        runBlocking {
            CompanionTestServer
                .of(responses = mapOf("/atc" to TestResponse(body = """{"ok":true,"cart_added":true}""")))
                .use { server ->
                    val executor = HttpRecGovAtcExecutor(RecGovAtcConfig(server.baseUrl, Duration.ofSeconds(5)))

                    executor.addToCart(atcPayload())

                    assertEquals(listOf("/atc"), server.paths)
                }
        }

    @Test
    fun `maps companion failure response`() =
        runBlocking {
            CompanionTestServer
                .of(
                    responses =
                        mapOf(
                            "/atc" to
                                TestResponse(
                                    status = 500,
                                    body = """{"ok":false,"cart_added":false,"error":"cart_not_added","detail":"no hold"}""",
                                ),
                        ),
                ).use { server ->
                    val executor = HttpRecGovAtcExecutor(RecGovAtcConfig(server.baseUrl, Duration.ofSeconds(5)))

                    val outcome = executor.addToCart(atcPayload())

                    val failed = outcome as RecGovAtcOutcome.Failed
                    assertEquals(listOf("/atc"), server.paths)
                    assertEquals("cart_not_added", failed.error)
                    assertEquals("no hold", failed.detail)
                }
        }

    @Test
    fun `an unreadable body never fabricates a companion response`() =
        runBlocking {
            CompanionTestServer
                .of(responses = mapOf("/atc" to TestResponse(body = "not json")))
                .use { server ->
                    val executor = HttpRecGovAtcExecutor(RecGovAtcConfig(server.baseUrl, Duration.ofSeconds(5)))

                    val failed = executor.addToCart(atcPayload()) as RecGovAtcOutcome.Failed

                    assertEquals("companion_invalid_response", failed.error)
                    assertEquals("not json", failed.detail)
                    assertNull(failed.response)
                }
        }

    @Test
    fun `sends the shared secret header`() =
        runBlocking {
            CompanionTestServer
                .of(responses = mapOf("/atc" to TestResponse(body = """{"ok":true,"cart_added":true}""")))
                .use { server ->
                    val executor =
                        HttpRecGovAtcExecutor(
                            RecGovAtcConfig(server.baseUrl, Duration.ofSeconds(5), COMPANION_TOKEN),
                        )

                    executor.addToCart(atcPayload())

                    assertEquals(listOf<String?>(COMPANION_TOKEN), server.companionTokens)
                }
        }

    @Test
    fun `omits the shared secret header when no token is configured`() =
        runBlocking {
            CompanionTestServer
                .of(responses = mapOf("/atc" to TestResponse(body = """{"ok":true,"cart_added":true}""")))
                .use { server ->
                    val executor = HttpRecGovAtcExecutor(RecGovAtcConfig(server.baseUrl, Duration.ofSeconds(5)))

                    executor.addToCart(atcPayload())

                    assertEquals(listOf<String?>(null), server.companionTokens)
                }
        }

    companion object {
        private const val COMPANION_TOKEN = "shared-companion-secret"

        private fun atcPayload() =
            buildJsonObject {
                put("profile_id", "91")
                put("start_date", "2026-07-19")
                put("end_date", "2026-07-20")
                put("campsite_id", "102524")
            }
    }
}
