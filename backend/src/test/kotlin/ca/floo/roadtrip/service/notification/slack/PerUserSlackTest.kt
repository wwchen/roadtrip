package ca.floo.roadtrip.service.notification.slack

import ca.floo.roadtrip.client.slack.SlackClient
import ca.floo.roadtrip.config.SlackConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies per-request token support on [SlackClient]:
 *  - authTest returns null on Slack {ok:false}
 *  - authTest returns a populated SlackIdentity on {ok:true}
 *  - token-parameterized postMessage sends with the CALLER's token, not the config token
 */
class PerUserSlackTest {
    private val configToken = "xoxb-config-token"
    private val config = SlackConfig(botToken = configToken, defaultChannel = "#default")

    private fun clientReturning(
        responseBody: String,
        capture: MutableMap<String, String?> = mutableMapOf(),
        status: HttpStatusCode = HttpStatusCode.OK,
    ): Pair<SlackClient, MutableMap<String, String?>> {
        val engine =
            MockEngine { req ->
                capture["url"] = req.url.toString()
                capture["auth"] = req.headers[HttpHeaders.Authorization]
                // GET requests (auth.test) have no body; ByteArrayContent only for POST
                capture["body"] = (req.body as? OutgoingContent.ByteArrayContent)?.bytes()?.let { String(it) } ?: ""
                respond(
                    content = responseBody,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        return SlackClient(config, HttpClient(engine)) to capture
    }

    @Test
    fun `authTest returns null when Slack rejects the token with ok false`() =
        runBlocking {
            val (client) = clientReturning("""{"ok":false,"error":"invalid_auth"}""")
            val result = client.authTest("bad-token")
            assertNull(result, "authTest must return null when Slack returns ok:false")
        }

    @Test
    fun `authTest returns a populated SlackIdentity when Slack accepts the token`() =
        runBlocking {
            val (client, capture) =
                clientReturning(
                    """{"ok":true,"team":"Acme Corp","bot_id":"B123","user":"roadtrip-bot"}""",
                )
            val result = client.authTest("xoxb-good-token")
            assertNotNull(result, "authTest must return a SlackIdentity on ok:true")
            assertEquals("Acme Corp", result.teamName)
            assertEquals("roadtrip-bot", result.botName)
            assertTrue(capture["url"]!!.contains("auth.test"), "must call auth.test endpoint, got: ${capture["url"]}")
            assertEquals("Bearer xoxb-good-token", capture["auth"], "must send caller's token, not config token")
        }

    @Test
    fun `token-parameterized postMessage sends with the caller token not the config token`() =
        runBlocking {
            val callerToken = "xoxb-caller-specific"
            val (client, capture) = clientReturning("""{"ok":true}""")

            val ok = client.postMessage(callerToken, "#channel", "hello", attachments = null)

            assertTrue(ok)
            assertEquals("Bearer $callerToken", capture["auth"], "must use caller token, not config '$configToken'")
            assertTrue(capture["body"]!!.contains("\"channel\":\"#channel\""))
            assertTrue(capture["body"]!!.contains("hello"))
        }

    @Test
    fun `zero-argument postMessage (global config token) still uses the config bot token`() =
        runBlocking {
            val (client, capture) = clientReturning("""{"ok":true}""")

            val ok = client.postMessage("#default-channel", "global message")

            assertTrue(ok)
            assertEquals("Bearer $configToken", capture["auth"], "global postMessage must use config token")
        }

    @Test
    fun `authTest returns null without throwing on network failure`() =
        runBlocking {
            val engine = MockEngine { throw java.io.IOException("connection reset") }
            val client = SlackClient(config, HttpClient(engine))
            val result = client.authTest("some-token")
            assertNull(result, "authTest must return null on network failure")
        }
}
