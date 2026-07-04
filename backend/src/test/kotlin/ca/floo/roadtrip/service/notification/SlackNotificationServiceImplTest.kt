package ca.floo.roadtrip.service.notification

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse

class SlackNotificationServiceImplTest {
    // The impl owns the SlackClient it builds from its config, so the enabled
    // path is covered by SlackClientTest (against a mock HTTP engine). Here we
    // only assert the disabled state: a null config sends nothing and never
    // throws — it just logs and returns false.
    @Test
    fun `a disabled service (null config) sends nothing and returns false`() =
        runBlocking {
            val service = SlackNotificationServiceImpl(config = null)
            assertFalse(service.sendMessage("hello camper"))
            assertFalse(service.sendMessage("with a channel", channel = "#camping"))
        }
}
