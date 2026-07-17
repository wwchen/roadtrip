package ca.floo.roadtrip.routes

import ca.floo.roadtrip.clients.resend.EmailDeliveryClient
import ca.floo.roadtrip.clients.resend.EmailDeliveryMessage
import ca.floo.roadtrip.config.EmailConfig
import ca.floo.roadtrip.routes.test.testEmailRoutes
import ca.floo.roadtrip.service.notification.email.EmailNotificationService
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

class TestEmailRoutesTest {
    private class RecordingEmailClient(
        private val result: Boolean = true,
    ) : EmailDeliveryClient {
        val messages = mutableListOf<EmailDeliveryMessage>()

        override suspend fun send(message: EmailDeliveryMessage): Boolean {
            messages += message
            return result
        }
    }

    @Test
    fun `POST test email sends the test message to requested recipient`() =
        testApplication {
            val emailClient = RecordingEmailClient()
            application {
                routing { testEmailRoutes(emailService(emailClient)) }
            }

            val response =
                client.post("/test/email") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"to":"camper@example.test"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(true, body["sent"]!!.jsonPrimitive.boolean)
            assertEquals("camper@example.test", body["to"]!!.jsonPrimitive.content)
            val message = emailClient.messages.single()
            assertEquals("camper@example.test", message.to)
            assertEquals("Roadtrip Alerts <alerts@example.test>", message.from)
        }

    @Test
    fun `POST test email rejects bad recipients without sending`() =
        testApplication {
            val emailClient = RecordingEmailClient()
            application {
                routing { testEmailRoutes(emailService(emailClient)) }
            }

            listOf(
                """{}""" to "missing_to",
                """{"to":"not-an-email"}""" to "invalid_to",
            ).forEach { (requestBody, expectedError) ->
                val response =
                    client.post("/test/email") {
                        contentType(ContentType.Application.Json)
                        setBody(requestBody)
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(expectedError, body["error"]!!.jsonPrimitive.content)
            }
            assertEquals(emptyList(), emailClient.messages)
        }

    @Test
    fun `POST test email reports unavailable when email config is disabled`() =
        testApplication {
            application {
                routing { testEmailRoutes(EmailNotificationService(config = null)) }
            }

            val response =
                client.post("/test/email") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"to":"camper@example.test"}""")
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("email_send_failed", body["error"]!!.jsonPrimitive.content)
        }

    private fun emailService(client: RecordingEmailClient) =
        EmailNotificationService(
            config =
                EmailConfig(
                    resendApiKey = "re_test",
                    from = "Roadtrip Alerts <alerts@example.test>",
                    defaultTo = emptyList(),
                ),
            client = client,
        )
}
