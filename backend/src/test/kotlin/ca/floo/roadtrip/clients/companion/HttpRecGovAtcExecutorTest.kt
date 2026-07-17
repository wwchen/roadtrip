package ca.floo.roadtrip.clients.companion

import ca.floo.roadtrip.config.RecGovAtcConfig
import ca.floo.roadtrip.service.booking.RecGovAtcOutcome
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpRecGovAtcExecutorTest {
    @Test
    fun `posts payload and maps success response`() =
        runBlocking {
            TestServer(
                responses =
                    mapOf(
                        "/health" to TestResponse(body = HEALTH_OK),
                        "/atc" to TestResponse(body = """{"ok":true,"cart_added":true}"""),
                    ),
            ).use { server ->
                val executor = HttpRecGovAtcExecutor(RecGovAtcConfig(server.baseUrl, Duration.ofSeconds(5)))

                val outcome = executor.addToCart(flatAtcPayload())

                assertTrue(outcome is RecGovAtcOutcome.Completed)
                assertEquals(listOf("/health", "/atc"), server.paths)
                assertEquals(flatAtcPayload().toString(), server.bodies.last())
            }
        }

    @Test
    fun `maps companion failure response`() =
        runBlocking {
            TestServer(
                responses =
                    mapOf(
                        "/health" to TestResponse(body = HEALTH_OK),
                        "/atc" to
                            TestResponse(
                                status = 500,
                                body = """{"ok":false,"cart_added":false,"error":"cart_not_added","detail":"no hold"}""",
                            ),
                    ),
            ).use { server ->
                val executor = HttpRecGovAtcExecutor(RecGovAtcConfig(server.baseUrl, Duration.ofSeconds(5)))

                val outcome = executor.addToCart(flatAtcPayload())

                val failed = outcome as RecGovAtcOutcome.Failed
                assertEquals(listOf("/health", "/atc"), server.paths)
                assertEquals("cart_not_added", failed.error)
                assertEquals("no hold", failed.detail)
            }
        }

    @Test
    fun `fails before atc when companion health reports recgov auth failure`() =
        runBlocking {
            TestServer(
                responses =
                    mapOf(
                        "/health" to
                            TestResponse(
                                body =
                                    """
                                    {
                                      "ok": true,
                                      "busy": false,
                                      "recgov_auth": {
                                        "login_status": "failed",
                                        "logged_in": false,
                                        "error": "recgov_not_authenticated",
                                        "detail": "run make recgov-login"
                                      }
                                    }
                                    """.trimIndent(),
                            ),
                        "/atc" to TestResponse(body = """{"ok":true,"cart_added":true}"""),
                    ),
            ).use { server ->
                val executor = HttpRecGovAtcExecutor(RecGovAtcConfig(server.baseUrl, Duration.ofSeconds(5)))

                val outcome = executor.addToCart(flatAtcPayload())

                val failed = outcome as RecGovAtcOutcome.Failed
                assertEquals(listOf("/health"), server.paths)
                assertEquals("recgov_not_authenticated", failed.error)
                assertEquals("run make recgov-login", failed.detail)
                val auth = failed.response!!["recgov_auth"]!!.jsonObject
                assertEquals("failed", auth["login_status"]!!.jsonPrimitive.content)
            }
        }

    @Test
    fun `fails before atc when companion is busy`() =
        runBlocking {
            TestServer(
                responses =
                    mapOf(
                        "/health" to TestResponse(body = """{"ok":true,"busy":true,"recgov_auth":{"login_status":"ok"}}"""),
                        "/atc" to TestResponse(body = """{"ok":true,"cart_added":true}"""),
                    ),
            ).use { server ->
                val executor = HttpRecGovAtcExecutor(RecGovAtcConfig(server.baseUrl, Duration.ofSeconds(5)))

                val outcome = executor.addToCart(flatAtcPayload())

                val failed = outcome as RecGovAtcOutcome.Failed
                assertEquals(listOf("/health"), server.paths)
                assertEquals("companion_health_not_ok", failed.error)
                assertEquals(
                    true,
                    failed.response!!["busy"]!!
                        .jsonPrimitive
                        .content
                        .toBoolean(),
                )
            }
        }

    @Test
    fun `health parse failure does not fabricate companion response`() =
        runBlocking {
            TestServer(
                responses =
                    mapOf(
                        "/health" to TestResponse(body = "not json"),
                    ),
            ).use { server ->
                val executor = HttpRecGovAtcExecutor(RecGovAtcConfig(server.baseUrl, Duration.ofSeconds(5)))

                val outcome = executor.addToCart(flatAtcPayload())

                val failed = outcome as RecGovAtcOutcome.Failed
                assertEquals(listOf("/health"), server.paths)
                assertEquals("companion_health_invalid_response", failed.error)
                assertEquals("not json", failed.detail)
                assertNull(failed.response)
            }
        }

    private data class TestResponse(
        val status: Int = 200,
        val body: String,
    )

    private class TestServer(
        private val responses: Map<String, TestResponse>,
    ) : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<String>()

        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val path = exchange.requestURI.path
                    val response = responses.getValue(path)
                    paths += path
                    bodies += exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                    val bytes = response.body.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("content-type", "application/json")
                    exchange.sendResponseHeaders(response.status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                this.executor = executor
                start()
            }

        val baseUrl: String = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    companion object {
        private const val HEALTH_OK = """{"ok":true,"busy":false,"recgov_auth":{"login_status":"ok","logged_in":true}}"""

        private fun flatAtcPayload() =
            buildJsonObject {
                put("start_date", "2026-07-19")
                put("end_date", "2026-07-20")
                put("campsite_id", "102524")
            }
    }
}
