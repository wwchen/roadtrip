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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlackClientTest {
    private val config = SlackConfig(botToken = "xoxb-test", defaultChannel = "#unused")

    private fun notifierReturning(
        status: HttpStatusCode,
        body: String,
        capture: MutableMap<String, String?> = mutableMapOf(),
    ): SlackClient {
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
        return SlackClient(config, HttpClient(engine))
    }

    private fun jsonBody(capture: Map<String, String?>) = Json.parseToJsonElement(capture["body"]!!).jsonObject

    @Test
    fun `posts to chat_postMessage with bearer token and channel, returns true on ok`() =
        runBlocking {
            val capture = mutableMapOf<String, String?>()
            val notifier = notifierReturning(HttpStatusCode.OK, """{"ok":true}""", capture)

            val ok = notifier.postMessage("#camping", "hello camper")

            assertTrue(ok)
            assertTrue(capture["url"]!!.contains("chat.postMessage"))
            assertEquals("Bearer xoxb-test", capture["auth"])
            assertTrue(capture["body"]!!.contains("\"channel\":\"#camping\""))
            assertTrue(capture["body"]!!.contains("hello camper"))
        }

    @Test
    fun `unfurls are disabled on every chat_postMessage`() =
        runBlocking {
            val capture = mutableMapOf<String, String?>()
            val notifier = notifierReturning(HttpStatusCode.OK, """{"ok":true}""", capture)

            notifier.postMessage("#camping", "hi")

            // The spec is explicit: no giant Recreation.gov photo unfurl below a
            // watch card. Both flags default off; the wire body must confirm it.
            assertTrue(capture["body"]!!.contains("\"unfurl_links\":false"), capture["body"])
            assertTrue(capture["body"]!!.contains("\"unfurl_media\":false"), capture["body"])
        }

    @Test
    fun `attachment posts keep fallback inside the attachment and omit visible top-level text`() =
        runBlocking {
            val capture = mutableMapOf<String, String?>()
            val notifier = notifierReturning(HttpStatusCode.OK, """{"ok":true}""", capture)

            notifier.postMessage(
                "#camping",
                "fallback",
                attachments =
                    listOf(
                        SlackAttachmentDto(
                            color = "#4cb96a",
                            blocks = listOf(SlackBlocks.header("hello")),
                        ),
                    ),
            )

            val body = jsonBody(capture)
            // The color bar is the whole point of using attachments — it must
            // survive serialization and sit under the attachment, not the top
            // level of the message.
            assertNull(body["text"], "attachment cards should not render duplicate top-level text")
            val attachment = body["attachments"]!!.jsonArray.single().jsonObject
            assertEquals("fallback", attachment["fallback"]!!.jsonPrimitive.content)
            assertEquals("#4cb96a", attachment["color"]!!.jsonPrimitive.content)
            assertTrue(attachment["blocks"].toString().contains("header"), attachment.toString())
        }

    @Test
    fun `postResponse posts to the exact response_url with no bearer token and replace_original set`() =
        runBlocking {
            val capture = mutableMapOf<String, String?>()
            val notifier = notifierReturning(HttpStatusCode.OK, "ok", capture)

            val ok = notifier.postResponse("https://hooks.slack.test/actions/abc", "updated")

            assertTrue(ok)
            assertEquals("https://hooks.slack.test/actions/abc", capture["url"])
            // Response-URL posts are unauthenticated (the URL itself is the
            // capability token) — a stray Bearer would be a leak into a URL we
            // don't control.
            assertEquals(null, capture["auth"])
            assertTrue(capture["body"]!!.contains("\"replace_original\":true"), capture["body"])
        }

    @Test
    fun `response_url attachment updates omit top-level text and keep attachment fallback`() =
        runBlocking {
            val capture = mutableMapOf<String, String?>()
            val notifier = notifierReturning(HttpStatusCode.OK, "ok", capture)

            val ok =
                notifier.postResponse(
                    "https://hooks.slack.test/actions/abc",
                    "updated fallback",
                    attachments = listOf(SlackAttachmentDto(color = "#626770", blocks = listOf(SlackBlocks.header("updated card")))),
                )

            assertTrue(ok)
            val body = jsonBody(capture)
            assertNull(body["text"], "edited attachment cards should not render duplicate top-level text")
            val attachment = body["attachments"]!!.jsonArray.single().jsonObject
            assertEquals("updated fallback", attachment["fallback"]!!.jsonPrimitive.content)
            assertEquals("true", body["replace_original"]!!.jsonPrimitive.content)
        }

    @Test
    fun `returns false without throwing when Slack replies ok false`() =
        runBlocking {
            val notifier = notifierReturning(HttpStatusCode.OK, """{"ok":false,"error":"channel_not_found"}""")
            assertFalse(notifier.postMessage("#nope", "x"))
        }

    @Test
    fun `returns false without throwing on a non-2xx response`() =
        runBlocking {
            val notifier = notifierReturning(HttpStatusCode.InternalServerError, "upstream boom")
            assertFalse(notifier.postMessage("#camping", "x"))
        }

    @Test
    fun `returns false without throwing when the request fails`() =
        runBlocking {
            val engine = MockEngine { throw IOException("connection reset") }
            val notifier = SlackClient(config, HttpClient(engine))
            assertFalse(notifier.postMessage("#camping", "x"))
        }
}
