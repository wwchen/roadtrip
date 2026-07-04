package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.slack.SlackClient
import ca.floo.roadtrip.config.SlackConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlackNotificationServiceImplTest {
    /** Records the (channel, text) it was asked to post and returns a fixed result. */
    private class RecordingSlackClient(
        private val result: Boolean,
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
    fun `sendMessage delegates to the client and returns its result`() =
        runBlocking {
            val client = RecordingSlackClient(result = true)
            val service = SlackNotificationServiceImpl(client)

            val ok = service.sendMessage("#camping", "hello camper")

            assertTrue(ok)
            assertEquals(listOf("#camping" to "hello camper"), client.posts)
        }

    @Test
    fun `sendMessage surfaces a client failure as false`() =
        runBlocking {
            val service = SlackNotificationServiceImpl(RecordingSlackClient(result = false))
            assertEquals(false, service.sendMessage("#camping", "x"))
        }
}
