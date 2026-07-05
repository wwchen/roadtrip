package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.slack.SlackBlockDto
import ca.floo.roadtrip.clients.slack.SlackClient
import ca.floo.roadtrip.config.SlackConfig
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlackNotificationServiceImplTest {
    /** Records what it was asked to post and returns a fixed result, so the impl's
     *  enabled path (channel resolution, blocks pass-through, result propagation)
     *  is exercised without a live workspace. */
    private class RecordingSlackClient(
        private val result: Boolean = true,
    ) : SlackClient(SlackConfig(botToken = "xoxb-test", defaultChannel = "#unused")) {
        data class Post(
            val channel: String,
            val text: String,
            val blocks: List<SlackBlockDto>?,
        )

        val posts = mutableListOf<Post>()

        override suspend fun postMessage(
            channel: String,
            text: String,
            blocks: List<SlackBlockDto>?,
        ): Boolean {
            posts += Post(channel, text, blocks)
            return result
        }
    }

    private fun service(
        client: RecordingSlackClient,
        defaultChannel: String = "#default",
    ) = SlackNotificationServiceImpl(SlackConfig(botToken = "xoxb-test", defaultChannel = defaultChannel), client = client)

    private fun watchStatus(state: WatchStatusNotice.State = WatchStatusNotice.State.WATCHING) =
        WatchStatusNotice(
            state = state,
            siteCount = 235,
            siteName = null,
            siteLoop = null,
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
    fun `sendWatchStatus renders blocks to the given channel and returns the client result`() =
        runBlocking {
            val client = RecordingSlackClient(result = true)
            val ok = service(client).sendWatchStatus(watchStatus(), "#camping")

            assertTrue(ok)
            assertEquals(1, client.posts.size)
            val post = client.posts.single()
            assertEquals("#camping", post.channel)
            assertTrue(post.text.contains("Watching"), post.text)
            assertTrue(!post.blocks.isNullOrEmpty(), "watch-status send carries Block Kit blocks")
        }

    @Test
    fun `sendWatchStatus falls back to the configured default channel`() =
        runBlocking {
            val client = RecordingSlackClient()
            service(client, defaultChannel = "#default").sendWatchStatus(watchStatus())

            assertEquals("#default", client.posts.single().channel)
        }

    @Test
    fun `sendWatchStatus surfaces a client failure as false`() =
        runBlocking {
            assertFalse(service(RecordingSlackClient(result = false)).sendWatchStatus(watchStatus()))
        }

    @Test
    fun `sendWatchOpenings renders blocks and forwards them to the client`() =
        runBlocking {
            val client = RecordingSlackClient()
            val ok =
                service(client).sendWatchOpenings(
                    startDate = LocalDate.of(2026, 8, 1),
                    endDate = LocalDate.of(2026, 8, 3),
                    openings =
                        listOf(
                            WatchOpening(
                                label = "Site 100",
                                loop = "Loop A",
                                siteType = "Tent",
                                date = LocalDate.of(2026, 8, 1),
                                campgroundId = 7L,
                                campground = "Kirk Creek",
                                bookingUrl = "https://example.test/book/100",
                            ),
                        ),
                    channel = "#camping",
                )

            assertTrue(ok)
            val post = client.posts.single()
            assertEquals("#camping", post.channel)
            assertTrue(post.text.contains("campsite"), post.text)
            assertTrue(!post.blocks.isNullOrEmpty(), "openings send carries Block Kit blocks")
        }

    @Test
    fun `sendWatchOpenings sends nothing when there are no openings`() =
        runBlocking {
            val client = RecordingSlackClient()
            val ok = service(client).sendWatchOpenings(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3), emptyList())

            assertFalse(ok)
            assertTrue(client.posts.isEmpty())
        }

    @Test
    fun `a disabled service (null config) sends nothing and returns false`() =
        runBlocking {
            val service = SlackNotificationServiceImpl(config = null)
            assertFalse(service.sendWatchStatus(watchStatus()))
            assertFalse(service.sendWatchStatus(watchStatus(), channel = "#camping"))
            assertFalse(
                service.sendWatchOpenings(
                    LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 8, 3),
                    listOf(WatchOpening("Site 100", null, null, LocalDate.of(2026, 8, 1), null, null, null)),
                ),
            )
        }
}
