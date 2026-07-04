package ca.floo.roadtrip.clients.slack

import ca.floo.roadtrip.config.SlackConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlackNotifierTest {
    private val config = SlackConfig(botToken = "xoxb-test", defaultChannel = "#unused")

    private fun notifierReturning(
        status: HttpStatusCode,
        body: String,
        capture: MutableMap<String, String?> = mutableMapOf(),
    ): SlackNotifier {
        val engine =
            MockEngine { req ->
                capture["url"] = req.url.toString()
                capture["auth"] = req.headers[HttpHeaders.Authorization]
                capture["body"] = String((req.body as OutgoingContent.ByteArrayContent).bytes())
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        return SlackNotifier(config, HttpClient(engine))
    }

    @Test
    fun `posts to chat_postMessage with bearer token and channel, returns true on ok`() =
        runBlocking {
            val capture = mutableMapOf<String, String?>()
            val notifier = notifierReturning(HttpStatusCode.OK, """{"ok":true}""", capture)

            val ok = notifier.notify("#camping", "hello camper")

            assertTrue(ok)
            assertTrue(capture["url"]!!.contains("chat.postMessage"))
            assertEquals("Bearer xoxb-test", capture["auth"])
            assertTrue(capture["body"]!!.contains("\"channel\":\"#camping\""))
            assertTrue(capture["body"]!!.contains("hello camper"))
        }

    @Test
    fun `returns false without throwing when Slack replies ok false`() =
        runBlocking {
            val notifier = notifierReturning(HttpStatusCode.OK, """{"ok":false,"error":"channel_not_found"}""")
            assertFalse(notifier.notify("#nope", "x"))
        }

    @Test
    fun `returns false without throwing on a non-2xx response`() =
        runBlocking {
            val notifier = notifierReturning(HttpStatusCode.InternalServerError, "upstream boom")
            assertFalse(notifier.notify("#camping", "x"))
        }

    @Test
    fun `returns false without throwing when the request fails`() =
        runBlocking {
            val engine = MockEngine { throw IOException("connection reset") }
            val notifier = SlackNotifier(config, HttpClient(engine))
            assertFalse(notifier.notify("#camping", "x"))
        }
}
