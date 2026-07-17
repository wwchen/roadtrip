package ca.floo.roadtrip.service.notification.common

import ca.floo.roadtrip.client.resend.EmailDeliveryClient
import ca.floo.roadtrip.client.resend.EmailDeliveryMessage
import ca.floo.roadtrip.client.slack.SlackAttachmentDto
import ca.floo.roadtrip.client.slack.SlackBlockDto
import ca.floo.roadtrip.client.slack.SlackClient
import ca.floo.roadtrip.config.EmailConfig
import ca.floo.roadtrip.config.SlackConfig
import ca.floo.roadtrip.service.notification.email.EmailNotificationService
import ca.floo.roadtrip.service.notification.slack.SlackNotificationService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationServicesTest {
    /** Records what it was asked to post and returns a fixed result, so the impl's
     *  enabled path (channel resolution, blocks pass-through, result propagation)
     *  is exercised without a live workspace. */
    private class RecordingSlackClient(
        private val result: Boolean = true,
    ) : SlackClient(SlackConfig(botToken = "xoxb-test", defaultChannel = "#unused")) {
        data class Post(
            val channel: String,
            val attachments: List<SlackAttachmentDto>?,
        )

        data class Response(
            val responseUrl: String,
            val attachments: List<SlackAttachmentDto>?,
        )

        val posts = mutableListOf<Post>()
        val responses = mutableListOf<Response>()

        override suspend fun postMessage(
            channel: String,
            text: String,
            blocks: List<SlackBlockDto>?,
            attachments: List<SlackAttachmentDto>?,
        ): Boolean {
            posts += Post(channel, attachments)
            return result
        }

        override suspend fun postResponse(
            responseUrl: String,
            text: String,
            blocks: List<SlackBlockDto>?,
            attachments: List<SlackAttachmentDto>?,
        ): Boolean {
            responses += Response(responseUrl, attachments)
            return result
        }
    }

    private class RecordingEmailClient(
        private val result: Boolean = true,
    ) : EmailDeliveryClient {
        val messages = mutableListOf<EmailDeliveryMessage>()

        override suspend fun send(message: EmailDeliveryMessage): Boolean {
            messages += message
            return result
        }
    }

    private fun service(
        client: RecordingSlackClient,
        defaultChannel: String = "#default",
    ) = SlackNotificationService(
        config = SlackConfig(botToken = "xoxb-test", defaultChannel = defaultChannel),
        client = client,
    )

    private fun emailService(client: RecordingEmailClient) =
        EmailNotificationService(
            config =
                EmailConfig(
                    resendApiKey = "re_test",
                    from = "Roadtrip Alerts <alerts@example.test>",
                ),
            client = client,
        )

    private fun fanout(
        slackClient: RecordingSlackClient,
        emailClient: RecordingEmailClient,
    ) = NotificationFanout(
        listOf(
            SlackNotificationService(
                config = SlackConfig(botToken = "xoxb-test", defaultChannel = "#default"),
                client = slackClient,
            ),
            EmailNotificationService(
                config =
                    EmailConfig(
                        resendApiKey = "re_test",
                        from = "Roadtrip Alerts <alerts@example.test>",
                    ),
                client = emailClient,
            ),
        ),
    )

    private fun watchStatus(state: WatchStatusNotice.State = WatchStatusNotice.State.WATCHING) =
        WatchStatusNotice(
            watchId = 1L,
            state = state,
            siteCount = 235,
            siteName = null,
            siteLoop = null,
            campgroundName = null,
            startDate = LocalDate.of(2026, 7, 11),
            endDate = LocalDate.of(2026, 7, 12),
            dashboardUrl = "https://grafana.test/d/reservable-watch-drill?var-watch_id=1",
            poiLinks =
                listOf(
                    WatchStatusNotice.PoiLink(
                        poiId = 7,
                        mapUrl = "https://app.test/?poi=7",
                        gridUrl = "https://grafana.test/d/availability-cell-matrix?var-poi_id=7",
                    ),
                ),
        )

    @Test
    fun `sendWatchStatus posts to the requested or default channel and returns the client result`() =
        runBlocking {
            val client = RecordingSlackClient(result = true)
            val ok = service(client).sendWatchStatus(watchStatus(), NotificationTarget.Slack("#camping"))

            assertTrue(ok)
            assertEquals(1, client.posts.size)
            assertEquals("#camping", client.posts.single().channel)
            service(client, defaultChannel = "#default").sendWatchStatus(watchStatus(), NotificationTarget.Slack())
            assertEquals("#default", client.posts.last().channel)
            assertFalse(
                service(RecordingSlackClient(result = false))
                    .sendWatchStatus(watchStatus(), NotificationTarget.Slack()),
            )
        }

    @Test
    fun `sendWatchStatus sends one email per target recipient`() =
        runBlocking {
            val client = RecordingEmailClient()
            val ok =
                emailService(client).sendWatchStatus(
                    watchStatus(),
                    NotificationTarget.Email(listOf("one@example.test", "two@example.test")),
                )

            assertTrue(ok)
            assertEquals(listOf("one@example.test", "two@example.test"), client.messages.map { it.to })
            val message = client.messages.first()
            assertEquals("Roadtrip Alerts <alerts@example.test>", message.from)
            assertEquals("Roadtrip watch #1: Watching for openings", message.subject)
            assertTrue(message.text.contains("Nothing open right now"), message.text)
            assertTrue(message.html.contains("Watch dashboard"), message.html)
        }

    @Test
    fun `sendWatchOpenings forwards openings to the requested channel`() =
        runBlocking {
            val client = RecordingSlackClient()
            val ok =
                service(client).sendWatchOpenings(
                    watchId = 42L,
                    startDate = LocalDate.of(2026, 8, 1),
                    endDate = LocalDate.of(2026, 8, 3),
                    openings =
                        listOf(
                            opening(),
                        ),
                    target = NotificationTarget.Slack(channel = "#camping"),
                )

            assertTrue(ok)
            val post = client.posts.single()
            assertEquals("#camping", post.channel)
            val emptyClient = RecordingSlackClient()
            val emptyOk =
                service(emptyClient).sendWatchOpenings(
                    1L,
                    LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 8, 3),
                    emptyList(),
                    target = NotificationTarget.Slack(),
                )

            assertFalse(emptyOk)
            assertTrue(emptyClient.posts.isEmpty())
        }

    @Test
    fun `sendWatchOpenings sends one email per target recipient`() =
        runBlocking {
            val client = RecordingEmailClient()
            val ok =
                emailService(client).sendWatchOpenings(
                    watchId = 42L,
                    startDate = LocalDate.of(2026, 8, 1),
                    endDate = LocalDate.of(2026, 8, 3),
                    openings = listOf(opening()),
                    target = NotificationTarget.Email(listOf("one@example.test", "two@example.test")),
                    appRootUrl = "https://roadtrip.example",
                )

            assertTrue(ok)
            assertEquals(listOf("one@example.test", "two@example.test"), client.messages.map { it.to })
            val message = client.messages.first()
            assertEquals("Roadtrip Alerts <alerts@example.test>", message.from)
        }

    @Test
    fun `sendWatchOpenings returns false when any notification target fails`() =
        runBlocking {
            val slackClient = RecordingSlackClient(result = true)
            val emailClient = RecordingEmailClient(result = false)
            val service =
                fanout(slackClient = slackClient, emailClient = emailClient)

            val ok =
                service.sendWatchOpenings(
                    watchId = 42L,
                    startDate = LocalDate.of(2026, 8, 1),
                    endDate = LocalDate.of(2026, 8, 3),
                    openings = listOf(opening()),
                    targets = listOf(NotificationTarget.Slack("#camping"), NotificationTarget.Email(listOf("one@example.test"))),
                )

            assertFalse(ok)
            assertEquals(1, slackClient.posts.size)
            assertEquals(1, emailClient.messages.size)
        }

    @Test
    fun `sendAtcResult chunks the full companion response instead of truncating`() =
        runBlocking {
            val client = RecordingSlackClient()
            val longLogLine = "confirmation-disabled ".repeat(180) + "tail-marker"
            val response =
                buildJsonObject {
                    put("ok", false)
                    put("cart_added", false)
                    put("error", "recgov_confirmation_disabled")
                    put(
                        "detail",
                        "Recreation.gov showed an add-to-cart confirmation step,\u00A0but no confirmation button became enabled.",
                    )
                    put("booking_url", "https://www.recreation.gov/camping/campsites/10174587")
                    putJsonObject("cart_check") {
                        put("reason", "cart_empty")
                        put("status", 200)
                        put("reservation_count", 0)
                        put("response_signal", false)
                    }
                    putJsonArray("screenshots") {
                        add(
                            buildJsonObject {
                                put("label", "confirmation-disabled")
                                put(
                                    "screenshot_url",
                                    "/screenshot/diagnostics/recgov-atc-confirmation-disabled.png",
                                )
                            },
                        )
                    }
                    putJsonArray("logs") {
                        add(longLogLine)
                    }
                }

            val ok =
                service(client).sendAtcResult(
                    watchId = 14L,
                    vendor = "recgov",
                    status = "failed",
                    request =
                        buildJsonObject {
                            put("start_date", "2026-07-19")
                            put("end_date", "2026-07-20")
                            put("campsite_id", "10174587")
                        },
                    response = response,
                    target = NotificationTarget.Slack("#camping"),
                )

            assertTrue(ok)
            val post = client.posts.single()
            assertEquals("#camping", post.channel)
            val blocks =
                post.attachments
                    ?.single()
                    ?.blocks
                    .orEmpty()
            val blockText =
                blocks
                    .flatMap { block ->
                        listOfNotNull(block.text?.text) + block.fields.orEmpty().map { it.text }
                    }.joinToString("\n")
            assertTrue(blockText.contains("recgov_confirmation_disabled"), blockText)
            assertTrue(blockText.contains("reason=`cart_empty`"), blockText)
            assertTrue(blockText.contains("recgov-atc-confirmation-disabled.png"), blockText)
            assertTrue(blockText.contains("tail-marker"), blockText)
            assertTrue(blockText.contains("*Companion response (2/"), blockText)
            assertFalse(blockText.contains("\n..."), blockText)
            assertFalse(blockText.contains('\u00A0'), blockText)
            assertFalse(blockText.contains("    \"ok\""), blockText)
        }

    @Test
    fun `response_url updates post back to Slack for status and stale-watch cards`() =
        runBlocking {
            val client = RecordingSlackClient(result = true)
            val svc = service(client)
            val ok =
                svc.postResponseWatchStatus("https://hooks.slack/actions/xyz", watchStatus(state = WatchStatusNotice.State.PAUSED))

            assertTrue(ok)
            val staleOk = svc.postResponseStaleWatch("https://hooks.slack/actions/xyz", watchId = 42L)

            assertTrue(staleOk)
            assertEquals(
                listOf("https://hooks.slack/actions/xyz", "https://hooks.slack/actions/xyz"),
                client.responses.map { it.responseUrl },
            )
        }

    @Test
    fun `a disabled service (null config) sends nothing and returns false`() =
        runBlocking {
            val slack = SlackNotificationService(config = null)
            val email = EmailNotificationService(config = null)
            val service = NotificationFanout(listOf(slack, email))
            assertFalse(slack.sendWatchStatus(watchStatus(), NotificationTarget.Slack()))
            assertFalse(slack.sendWatchStatus(watchStatus(), NotificationTarget.Slack("#camping")))
            assertFalse(
                email.sendWatchStatus(
                    watchStatus(),
                    NotificationTarget.Email(listOf("one@example.test")),
                ),
            )
            assertFalse(slack.sendTestMessage("#camping"))
            assertFalse(
                service.sendWatchOpenings(
                    1L,
                    LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 8, 3),
                    listOf(opening()),
                    targets = listOf(NotificationTarget.Slack(), NotificationTarget.Email()),
                ),
            )
            assertFalse(slack.postResponseWatchStatus("https://hooks.slack/actions/xyz", watchStatus()))
            assertFalse(slack.postResponseStaleWatch("https://hooks.slack/actions/xyz", watchId = 42L))
        }

    private fun opening(): WatchOpening =
        WatchOpening(
            label = "Site 100",
            loop = "Loop A",
            siteType = "Tent",
            date = LocalDate.of(2026, 8, 1),
            campgroundId = 7L,
            campground = "Kirk Creek",
            bookingUrl = "https://example.test/book/100",
            vendor = "recgov",
        )
}
