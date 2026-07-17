package ca.floo.roadtrip.routes

import ca.floo.roadtrip.clients.slack.SlackAttachmentDto
import ca.floo.roadtrip.clients.slack.SlackBlockDto
import ca.floo.roadtrip.clients.slack.SlackClient
import ca.floo.roadtrip.config.SlackConfig
import ca.floo.roadtrip.service.notification.slack.SlackNotificationService
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TOO_LONG_SLACK_CHANNEL_CHARS = 256

class TestSlackRoutesTest {
    private class RecordingSlackClient(
        private val result: Boolean = true,
    ) : SlackClient(SlackConfig(botToken = "xoxb-test", defaultChannel = "#unused")) {
        data class Post(
            val channel: String,
            val text: String,
            val blocks: List<SlackBlockDto>?,
            val attachments: List<SlackAttachmentDto>?,
        )

        val posts = mutableListOf<Post>()

        override suspend fun postMessage(
            channel: String,
            text: String,
            blocks: List<SlackBlockDto>?,
            attachments: List<SlackAttachmentDto>?,
        ): Boolean {
            posts += Post(channel, text, blocks, attachments)
            return result
        }
    }

    @Test
    fun `POST test slack sends the test message to requested channel`() =
        testApplication {
            val slackClient = RecordingSlackClient()
            application {
                routing { testSlackRoutes(slackService(slackClient)) }
            }

            val response =
                client.post("/test/slack") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"channel":" #camping "}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(true, body["sent"]!!.jsonPrimitive.boolean)
            assertEquals("#camping", body["channel"]!!.jsonPrimitive.content)
            val post = slackClient.posts.single()
            assertEquals("#camping", post.channel)
            assertEquals("Roadtrip test Slack message", post.text)
            assertTrue(!post.attachments.isNullOrEmpty(), "test slack send carries an attachment")
        }

    @Test
    fun `POST test slack uses default channel when no override is provided`() =
        testApplication {
            val slackClient = RecordingSlackClient()
            application {
                routing { testSlackRoutes(slackService(slackClient, defaultChannel = "#default")) }
            }

            val response =
                client.post("/test/slack") {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(true, body["sent"]!!.jsonPrimitive.boolean)
            assertFalse(body.containsKey("channel"))
            assertEquals("#default", slackClient.posts.single().channel)
        }

    @Test
    fun `POST test slack rejects overly long channel override`() =
        testApplication {
            val slackClient = RecordingSlackClient()
            application {
                routing { testSlackRoutes(slackService(slackClient)) }
            }

            val response =
                client.post("/test/slack") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"channel":"${"x".repeat(TOO_LONG_SLACK_CHANNEL_CHARS)}"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalid_channel", body["error"]!!.jsonPrimitive.content)
            assertEquals(emptyList(), slackClient.posts)
        }

    @Test
    fun `POST test slack reports unavailable when slack config is disabled`() =
        testApplication {
            application {
                routing { testSlackRoutes(SlackNotificationService(config = null)) }
            }

            val response =
                client.post("/test/slack") {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("slack_send_failed", body["error"]!!.jsonPrimitive.content)
        }

    private fun slackService(
        client: RecordingSlackClient,
        defaultChannel: String = "#default",
    ) = SlackNotificationService(
        config = SlackConfig(botToken = "xoxb-test", defaultChannel = defaultChannel),
        client = client,
    )
}
