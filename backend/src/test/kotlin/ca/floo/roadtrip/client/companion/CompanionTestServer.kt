package ca.floo.roadtrip.client.companion

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

internal data class TestResponse(
    val status: Int = 200,
    val body: String,
)

/**
 * A loopback stand-in for the companion, shared by the two clients that talk to
 * it. Records the path, query, body and `x-companion-token` of every request.
 *
 * A path maps to a *list* of responses so a two-phase exchange — a login that
 * answers `mfa_required` and then, on the second POST, `ok` — can be scripted on
 * one path. The last entry repeats once the list is exhausted, which keeps the
 * single-response case a one-element list.
 */
internal class CompanionTestServer(
    private val responses: Map<String, List<TestResponse>>,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val callCounts = mutableMapOf<String, Int>()
    val paths = mutableListOf<String>()
    val queries = mutableListOf<String?>()
    val bodies = mutableListOf<String>()
    val companionTokens = mutableListOf<String?>()

    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val scripted = responses.getValue(path)
                val seen = callCounts.getOrDefault(path, 0)
                callCounts[path] = seen + 1
                val response = scripted[minOf(seen, scripted.size - 1)]
                paths += path
                queries += exchange.requestURI.query
                companionTokens += exchange.requestHeaders.getFirst("x-companion-token")
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

    companion object {
        /** One fixed response per path — the common case. */
        fun of(responses: Map<String, TestResponse>): CompanionTestServer = CompanionTestServer(responses.mapValues { listOf(it.value) })
    }
}
