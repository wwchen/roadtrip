package ca.floo.roadtrip.routes

import ca.floo.roadtrip.clients.slack.SlackSignatureVerifier
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.availability.WatchStatus
import ca.floo.roadtrip.service.notification.SlackInteractivityHandler
import ca.floo.roadtrip.service.notification.SlackNotificationService
import ca.floo.roadtrip.service.notification.SlackWatchCard
import ca.floo.roadtrip.service.notification.WatchOpening
import ca.floo.roadtrip.service.notification.WatchStatusNotice
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlackInteractivityRoutesTest {
    private val secret = "test-secret-abc123"
    private val now = Instant.parse("2026-07-06T12:00:00Z")

    private fun verifier() = SlackSignatureVerifier(secret, Clock.fixed(now, ZoneOffset.UTC))

    /** Signs [body] the way Slack does so the fake request the test builds is
     *  what the route expects to see over the wire. */
    private fun sign(
        body: String,
        ts: Long = now.epochSecond,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val hex = mac.doFinal("v0:$ts:$body".toByteArray()).joinToString("") { "%02x".format(it) }
        return "v0=$hex"
    }

    /** Form-encoded payload — Slack always URL-encodes the JSON blob and puts
     *  it in the single `payload` field. */
    private fun formBody(payloadJson: String): String = "payload=" + URLEncoder.encode(payloadJson, StandardCharsets.UTF_8)

    /** Fake watch layer for the route test; captures whether the handler was
     *  called at all. Deep behavior is exercised in SlackInteractivityHandlerTest. */
    private class RecordingWatches : SlackInteractivityHandler.Watches {
        var calls = 0

        override fun setStatus(
            id: Long,
            status: WatchStatus,
        ): AvailabilityWatchRepo.Watch? {
            calls++
            return null
        }

        override fun snapshotAndDelete(id: Long): AvailabilityWatchRepo.Watch? {
            calls++
            return null
        }

        override fun buildStatusNotice(
            watch: AvailabilityWatchRepo.Watch,
            state: WatchStatusNotice.State,
        ) = throw AssertionError("unused in these tests")
    }

    private class SilentSlack : SlackNotificationService {
        override suspend fun sendWatchStatus(
            notice: WatchStatusNotice,
            channel: String?,
        ) = true

        override suspend fun sendWatchOpenings(
            watchId: Long,
            startDate: LocalDate,
            endDate: LocalDate,
            openings: List<WatchOpening>,
            channel: String?,
            appRootUrl: String?,
        ) = true

        override suspend fun postResponseWatchStatus(
            responseUrl: String,
            notice: WatchStatusNotice,
        ) = true
    }

    @Test
    fun `verified request is ack'd 200 and forwarded to the handler`() =
        testApplication {
            val watches = RecordingWatches()
            val handler = SlackInteractivityHandler(watches = watches, slack = SilentSlack())
            application {
                routing {
                    slackInteractivityRoute(verifier(), handler, CoroutineScope(Dispatchers.Unconfined))
                }
            }
            val body =
                formBody(
                    """{"type":"block_actions","response_url":"https://hooks.slack.test/x","actions":[{"action_id":"${SlackWatchCard.ACTION_WATCH_PAUSE}","value":"42","type":"button"}]}""",
                )
            val resp =
                client.post("/api/slack/interactivity") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    header("X-Slack-Request-Timestamp", now.epochSecond.toString())
                    header("X-Slack-Signature", sign(body))
                    setBody(body)
                }

            assertEquals(HttpStatusCode.OK, resp.status, "Slack requires a 200 within 3s")
            // Handler ran on the Unconfined dispatcher so setStatus fired
            // synchronously before the response returned.
            assertEquals(1, watches.calls, "handler should have seen the payload")
        }

    @Test
    fun `tampered body returns 401 and does not touch the handler`() =
        testApplication {
            val watches = RecordingWatches()
            val handler = SlackInteractivityHandler(watches = watches, slack = SilentSlack())
            application {
                routing {
                    slackInteractivityRoute(verifier(), handler, CoroutineScope(Dispatchers.Unconfined))
                }
            }
            val originalBody = formBody("""{"type":"block_actions","actions":[]}""")
            val tamperedBody = formBody("""{"type":"block_actions","actions":[{"action_id":"watch_pause","value":"42"}]}""")
            val resp =
                client.post("/api/slack/interactivity") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    header("X-Slack-Request-Timestamp", now.epochSecond.toString())
                    header("X-Slack-Signature", sign(originalBody)) // signed the OLD body
                    setBody(tamperedBody) // sent a DIFFERENT body
                }

            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals(0, watches.calls, "unverified requests must never reach the handler")
        }

    @Test
    fun `missing signature headers return 401`() =
        testApplication {
            val watches = RecordingWatches()
            val handler = SlackInteractivityHandler(watches = watches, slack = SilentSlack())
            application {
                routing {
                    slackInteractivityRoute(verifier(), handler, CoroutineScope(Dispatchers.Unconfined))
                }
            }
            val body = formBody("""{"type":"block_actions","actions":[]}""")
            val resp =
                client.post("/api/slack/interactivity") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(body)
                }

            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            assertEquals(0, watches.calls)
        }

    @Test
    fun `verified request with no payload form field returns 400`() =
        testApplication {
            val watches = RecordingWatches()
            val handler = SlackInteractivityHandler(watches = watches, slack = SilentSlack())
            application {
                routing {
                    slackInteractivityRoute(verifier(), handler, CoroutineScope(Dispatchers.Unconfined))
                }
            }
            val body = "other=stuff" // valid form encoding but no payload key
            val resp =
                client.post("/api/slack/interactivity") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    header("X-Slack-Request-Timestamp", now.epochSecond.toString())
                    header("X-Slack-Signature", sign(body))
                    setBody(body)
                }

            // Signature verified — Slack really did send this — but the body
            // isn't a block_actions submission, so reject rather than silently
            // no-op.
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertTrue(watches.calls == 0)
        }

    @Test
    fun `verified request with non-block_actions garbage returns 400`() =
        testApplication {
            val watches = RecordingWatches()
            val handler = SlackInteractivityHandler(watches = watches, slack = SilentSlack())
            application {
                routing {
                    slackInteractivityRoute(verifier(), handler, CoroutineScope(Dispatchers.Unconfined))
                }
            }
            val body = formBody("this is not json at all")
            val resp =
                client.post("/api/slack/interactivity") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    header("X-Slack-Request-Timestamp", now.epochSecond.toString())
                    header("X-Slack-Signature", sign(body))
                    setBody(body)
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
}
