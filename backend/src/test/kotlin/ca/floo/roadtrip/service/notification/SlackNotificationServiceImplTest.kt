package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.slack.SlackClient
import ca.floo.roadtrip.config.SlackConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlackNotificationServiceImplTest {
    /** Records the (channel, text) it was asked to post and returns a fixed result. */
    private class RecordingSlackClient(
        private val result: Boolean = true,
    ) : SlackClient(SlackConfig(botToken = "xoxb-test", defaultChannel = "#unused")) {
        val posts = mutableListOf<Pair<String, String>>()

        override suspend fun postMessage(
            channel: String,
            text: String,
        ): Boolean {
            posts += channel to text
            return result
        }
    }

    @Test
    fun `sends to the given channel and returns the client result`() =
        runBlocking {
            val client = RecordingSlackClient(result = true)
            val service = SlackNotificationServiceImpl(client, defaultChannel = "#default")

            val ok = service.sendMessage("hello camper", "#camping")

            assertTrue(ok)
            assertEquals(listOf("#camping" to "hello camper"), client.posts)
        }

    @Test
    fun `falls back to the default channel when none is given`() =
        runBlocking {
            val client = RecordingSlackClient()
            SlackNotificationServiceImpl(client, defaultChannel = "#default").sendMessage("hi")

            assertEquals(listOf("#default" to "hi"), client.posts)
        }

    @Test
    fun `returns false and sends nothing when there is no channel and no default`() =
        runBlocking {
            val client = RecordingSlackClient()
            val ok = SlackNotificationServiceImpl(client, defaultChannel = null).sendMessage("hi")

            assertFalse(ok)
            assertTrue(client.posts.isEmpty())
        }

    @Test
    fun `surfaces a client failure as false`() =
        runBlocking {
            val service = SlackNotificationServiceImpl(RecordingSlackClient(result = false), defaultChannel = "#default")
            assertFalse(service.sendMessage("x"))
        }
}
