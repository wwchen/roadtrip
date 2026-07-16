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
import kotlin.test.assertTrue

class HttpRecGovAtcExecutorTest {
    @Test
    fun `posts payload and maps success response`() =
        runBlocking {
            TestServer(
                responses =
                    mapOf(
                        "/health" to TestResponse(body = HEALTH_OK),
                        "/recgov/atc" to TestResponse(body = """{"ok":true,"cart_added":true}"""),
                    ),
            ).use { server ->
                val executor = HttpRecGovAtcExecutor(RecGovAtcConfig(server.baseUrl, Duration.ofSeconds(5)))

                val outcome = executor.addToCart(buildJsonObject { put("watch_id", 42L) })

                assertTrue(outcome is RecGovAtcOutcome.Completed)
                assertEquals(listOf("/health", "/recgov/atc"), server.paths)
                assertEquals("""{"watch_id":42}""", server.bodies.last())
            }
        }

    @Test
    fun `maps companion failure response`() =
        runBlocking {
            TestServer(
                responses =
                    mapOf(
                        "/health" to TestResponse(body = HEALTH_OK),
                        "/recgov/atc" to
                            TestResponse(
                                status = 500,
                                body = """{"ok":false,"cart_added":false,"error":"cart_not_added","detail":"no hold"}""",
                            ),
                    ),
            ).use { server ->
                val executor = HttpRecGovAtcExecutor(RecGovAtcConfig(server.baseUrl, Duration.ofSeconds(5)))

                val outcome = executor.addToCart(buildJsonObject { put("watch_id", 42L) })

                val failed = outcome as RecGovAtcOutcome.Failed
                assertEquals(listOf("/health", "/recgov/atc"), server.paths)
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
                        "/recgov/atc" to TestResponse(body = """{"ok":true,"cart_added":true}"""),
                    ),
            ).use { server ->
                val executor = HttpRecGovAtcExecutor(RecGovAtcConfig(server.baseUrl, Duration.ofSeconds(5)))

                val outcome = executor.addToCart(buildJsonObject { put("watch_id", 42L) })

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
                        "/recgov/atc" to TestResponse(body = """{"ok":true,"cart_added":true}"""),
                    ),
            ).use { server ->
                val executor = HttpRecGovAtcExecutor(RecGovAtcConfig(server.baseUrl, Duration.ofSeconds(5)))

                val outcome = executor.addToCart(buildJsonObject { put("watch_id", 42L) })

                val failed = outcome as RecGovAtcOutcome.Failed
                assertEquals(listOf("/health"), server.paths)
                assertEquals("companion_busy", failed.error)
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
    }
}
