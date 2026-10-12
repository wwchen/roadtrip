package ca.floo.roadtrip.route

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private const val ORIGIN_PATH = "/test/origin"

/** What `installRoadtripPlugins` makes of the forwarded headers: the caller's address, and nothing else. */
class ForwardedHeadersTest {
    @Test
    fun `the caller comes from X-Forwarded-For, and the forwarded scheme and host are ignored`() =
        testApplication {
            application { routeTestApplication { originEcho() } }

            val body =
                client
                    .get(ORIGIN_PATH) {
                        header("X-Forwarded-For", "192.0.2.99, 203.0.113.7, 172.18.0.5")
                        header("X-Forwarded-Proto", "https")
                        header("X-Forwarded-Host", "attacker.example")
                    }.bodyAsText()

            assertEquals("203.0.113.7|http|localhost", body)
        }

    @Test
    fun `without the header the caller is the socket's peer`() =
        testApplication {
            application { routeTestApplication { originEcho() } }

            assertEquals("localhost|http|localhost", client.get(ORIGIN_PATH).bodyAsText())
        }

    private fun io.ktor.server.routing.Route.originEcho() {
        get(ORIGIN_PATH) {
            val origin = call.request.origin
            call.respondText("${origin.remoteHost}|${origin.scheme}|${origin.serverHost}")
        }
    }
}
