package ca.floo.roadtrip.clients.companion

import ca.floo.roadtrip.config.RecGovAtcConfig
import ca.floo.roadtrip.service.booking.RecGovAtcOutcome
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
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
            TestServer(status = 200, body = """{"ok":true,"cart_added":true}""").use { server ->
                val executor = HttpRecGovAtcExecutor(RecGovAtcConfig(server.baseUrl, Duration.ofSeconds(5)))

                val outcome = executor.addToCart(buildJsonObject { put("watch_id", 42L) })

                assertTrue(outcome is RecGovAtcOutcome.Completed)
                assertEquals("/recgov/atc", server.lastPath)
                assertEquals("""{"watch_id":42}""", server.lastBody)
            }
        }

    @Test
    fun `maps companion failure response`() =
        runBlocking {
            TestServer(
                status = 500,
                body = """{"ok":false,"cart_added":false,"error":"cart_not_added","detail":"no hold"}""",
            ).use { server ->
                val executor = HttpRecGovAtcExecutor(RecGovAtcConfig(server.baseUrl, Duration.ofSeconds(5)))

                val outcome = executor.addToCart(buildJsonObject { put("watch_id", 42L) })

                val failed = outcome as RecGovAtcOutcome.Failed
                assertEquals("cart_not_added", failed.error)
                assertEquals("no hold", failed.detail)
            }
        }

    private class TestServer(
        private val status: Int,
        private val body: String,
    ) : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        var lastPath: String? = null
        var lastBody: String? = null

        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    lastPath = exchange.requestURI.path
                    lastBody = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("content-type", "application/json")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
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
}
