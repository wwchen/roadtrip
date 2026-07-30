package ca.floo.roadtrip.route

import ca.floo.roadtrip.client.slack.SlackAttachmentDto
import ca.floo.roadtrip.client.slack.SlackBlockDto
import ca.floo.roadtrip.client.slack.SlackClient
import ca.floo.roadtrip.config.SlackConfig
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.route.auth.SESSION_COOKIE
import ca.floo.roadtrip.route.auth.roadtripAuthorization
import ca.floo.roadtrip.route.test.testSlackRoutes
import ca.floo.roadtrip.service.notification.slack.SlackNotificationService
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val TOO_LONG_SLACK_CHANNEL_CHARS = 256
private const val SLACK_USER_TOKEN = "user-token"
private val slackTestUserId = UserId(42L)

private fun resolve(token: String?): Principal =
    when (token) {
        SLACK_USER_TOKEN -> Principal.User(slackTestUserId, roles = emptySet())
        else -> Principal.Anonymous
    }

private fun HttpRequestBuilder.userSession() {
    header(HttpHeaders.Cookie, "$SESSION_COOKIE=$SLACK_USER_TOKEN")
}

class TestSlackRoutesTest {
    private class RecordingSlackClient(
        private val result: Boolean = true,
    ) : SlackClient(SlackConfig(botToken = "xoxb-test", defaultChannel = "#unused")) {
        data class Post(
            val channel: String,
        )

        val posts = mutableListOf<Post>()

        override suspend fun postMessage(
            channel: String,
            text: String,
            blocks: List<SlackBlockDto>?,
            attachments: List<SlackAttachmentDto>?,
        ): Boolean {
            posts += Post(channel)
            return result
        }
    }

    @Test
    fun `POST test slack anonymous returns 401 without posting`() =
        testApplication {
            val slackClient = RecordingSlackClient()
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing { testSlackRoutes(slackService(slackClient)) }
            }

            val response =
                client.post("/test/slack") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"channel":"#camping"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(emptyList(), slackClient.posts)
        }

    @Test
    fun `POST test slack sends requested or default channel`() =
        testApplication {
            val slackClient = RecordingSlackClient()
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing { testSlackRoutes(slackService(slackClient, defaultChannel = "#default")) }
            }

            val overrideResponse =
                client.post("/test/slack") {
                    userSession()
                    contentType(ContentType.Application.Json)
                    setBody("""{"channel":" #camping "}""")
                }

            assertEquals(HttpStatusCode.OK, overrideResponse.status)
            val overrideBody = Json.parseToJsonElement(overrideResponse.bodyAsText()).jsonObject
            assertEquals(true, overrideBody["sent"]!!.jsonPrimitive.boolean)
            assertEquals("#camping", overrideBody["channel"]!!.jsonPrimitive.content)
            assertEquals("#camping", slackClient.posts.single().channel)

            val defaultResponse =
                client.post("/test/slack") {
                    userSession()
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }

            assertEquals(HttpStatusCode.OK, defaultResponse.status)
            val defaultBody = Json.parseToJsonElement(defaultResponse.bodyAsText()).jsonObject
            assertEquals(true, defaultBody["sent"]!!.jsonPrimitive.boolean)
            assertFalse(defaultBody.containsKey("channel"))
            assertEquals("#default", slackClient.posts.last().channel)
        }

    @Test
    fun `POST test slack rejects overly long channel override`() =
        testApplication {
            val slackClient = RecordingSlackClient()
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing { testSlackRoutes(slackService(slackClient)) }
            }

            val response =
                client.post("/test/slack") {
                    userSession()
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
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing { testSlackRoutes(SlackNotificationService(config = null)) }
            }

            val response =
                client.post("/test/slack") {
                    userSession()
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
        slackClient = client,
    )
}
