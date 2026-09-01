package ca.floo.roadtrip.service.notification.slack

import ca.floo.roadtrip.client.slack.SlackClient
import ca.floo.roadtrip.service.notification.common.NotificationTarget
import ca.floo.roadtrip.service.notification.common.WatchStatusNotice
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies SlackNotificationService works with null config + a per-user-only transport:
 *  - per-user token path sends via the owner's token, not the disabled global config
 *  - null token + null config is disabled → false (no token to send with)
 */
class SlackNotificationServiceTest {
    private val start = LocalDate.of(2026, 7, 11)
    private val end = LocalDate.of(2026, 7, 12)

    private fun clientReturning(
        responseBody: String,
        capture: MutableMap<String, String?> = mutableMapOf(),
        status: HttpStatusCode = HttpStatusCode.OK,
    ): Pair<SlackClient, MutableMap<String, String?>> {
        val engine =
            MockEngine { req ->
                capture["url"] = req.url.toString()
                capture["auth"] = req.headers[HttpHeaders.Authorization]
                capture["body"] = (req.body as? OutgoingContent.ByteArrayContent)?.bytes()?.let { String(it) } ?: ""
                respond(
                    content = responseBody,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        return SlackClient(config = null, httpClient = HttpClient(engine)) to capture
    }

    @Test
    fun `a failed ATC card names the reason the caller gave it`() =
        runBlocking {
            // A preflight failure never reaches the companion, so there is no
            // response object to dig a reason out of — the card would otherwise
            // say only "failed". The email side has producer-to-renderer
            // coverage; this is the Slack half.
            val (client, capture) = clientReturning("""{"ok":true}""")
            val service = SlackNotificationService(config = null, slackClient = client)

            val sent =
                service.sendAtcResult(
                    watchId = 42,
                    vendor = "recgov",
                    status = "failed",
                    request = buildJsonObject { put("campsite_id", "102524") },
                    response = null,
                    error = "recgov_session_expired",
                    detail = "session expired — re-login in Settings",
                    target = NotificationTarget.Slack(channel = "#owner-channel", token = "xoxb-owner"),
                )

            assertTrue(sent)
            val body = capture["body"].orEmpty()
            assertTrue(body.contains("*Reason*"), body)
            assertTrue(body.contains("recgov_session_expired"), body)
            assertTrue(body.contains("re-login in Settings"), body)
        }

    @Test
    fun `a completed ATC card carries no reason block`() =
        runBlocking {
            val (client, capture) = clientReturning("""{"ok":true}""")
            val service = SlackNotificationService(config = null, slackClient = client)

            service.sendAtcResult(
                watchId = 42,
                vendor = "recgov",
                status = "completed",
                request = buildJsonObject { put("campsite_id", "102524") },
                response = buildJsonObject { put("cart_added", true) },
                target = NotificationTarget.Slack(channel = "#owner-channel", token = "xoxb-owner"),
            )

            assertFalse(capture["body"].orEmpty().contains("*Reason*"))
        }

    @Test
    fun `sendWatchStatus uses owner token when config is null but client is present`() =
        runBlocking {
            val ownerToken = "xoxb-owner-specific"
            val (client, capture) = clientReturning("""{"ok":true}""")
            val service = SlackNotificationService(config = null, slackClient = client)

            val notice =
                WatchStatusNotice(
                    watchId = 42,
                    state = WatchStatusNotice.State.WATCHING,
                    siteCount = 10,
                    siteName = null,
                    siteLoop = null,
                    campgroundName = "Test Camp",
                    startDate = start,
                    endDate = end,
                    poiLinks = emptyList(),
                )
            val target = NotificationTarget.Slack(channel = "#owner-channel", token = ownerToken)

            val sent = service.sendWatchStatus(notice, target)

            assertTrue(sent, "message must send with owner token even when config is null")
            assertEquals("Bearer $ownerToken", capture["auth"], "must use owner's token, not global config")
            assertTrue(capture["body"]!!.contains("\"channel\":\"#owner-channel\""), "must post to owner's channel")
        }

    @Test
    fun `sendWatchStatus returns false when token and config are both null`() =
        runBlocking {
            val (client, capture) = clientReturning("""{"ok":true}""")
            val service = SlackNotificationService(config = null, slackClient = client)

            val notice =
                WatchStatusNotice(
                    watchId = 42,
                    state = WatchStatusNotice.State.WATCHING,
                    siteCount = 10,
                    siteName = null,
                    siteLoop = null,
                    campgroundName = "Test Camp",
                    startDate = start,
                    endDate = end,
                    poiLinks = emptyList(),
                )
            // No owner token, no global config → nothing to send with.
            val target = NotificationTarget.Slack(channel = "#channel", token = null)

            val sent = service.sendWatchStatus(notice, target)

            // Should be disabled and return false, not throw.
            assertTrue(!sent, "must return false (disabled) when both token and config are null")
            assertTrue(capture["body"].isNullOrEmpty(), "must not post anything when disabled")
        }
}
