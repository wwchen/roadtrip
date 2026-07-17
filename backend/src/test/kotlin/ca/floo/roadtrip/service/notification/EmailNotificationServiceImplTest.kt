package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.resend.EmailDeliveryClient
import ca.floo.roadtrip.clients.resend.EmailDeliveryMessage
import ca.floo.roadtrip.config.EmailConfig
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmailNotificationServiceImplTest {
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
    fun `sendWatchOpenings renders one email per configured recipient`() =
        runBlocking {
            val client = RecordingEmailClient()
            val service =
                EmailNotificationServiceImpl(
                    config =
                        EmailConfig(
                            resendApiKey = "re_test",
                            from = "Roadtrip Alerts <alerts@example.test>",
                            defaultTo = listOf("one@example.test", "two@example.test"),
                        ),
                    client = client,
                )

            val ok =
                service.sendWatchOpenings(
                    watchId = 42L,
                    startDate = LocalDate.of(2026, 8, 1),
                    endDate = LocalDate.of(2026, 8, 3),
                    openings = listOf(opening()),
                    appRootUrl = "https://roadtrip.example",
                )

            assertTrue(ok)
            assertEquals(listOf("one@example.test", "two@example.test"), client.messages.map { it.to })
            val message = client.messages.first()
            assertEquals("Roadtrip Alerts <alerts@example.test>", message.from)
            assertTrue(message.subject.contains("1 site opened"), message.subject)
            assertTrue(message.text.contains("Site 100"), message.text)
            assertTrue(message.text.contains("https://example.test/book/100"), message.text)
            assertTrue(message.html.contains("Open watch"), message.html)
        }

    @Test
    fun `sendWatchOpenings returns false when email is disabled`() =
        runBlocking {
            val service = EmailNotificationServiceImpl(config = null)

            assertFalse(
                service.sendWatchOpenings(
                    watchId = 42L,
                    startDate = LocalDate.of(2026, 8, 1),
                    endDate = LocalDate.of(2026, 8, 3),
                    openings = listOf(opening()),
                ),
            )
        }

    @Test
    fun `sendWatchOpenings returns false when any recipient send fails`() =
        runBlocking {
            val client = RecordingEmailClient(result = false)
            val service =
                EmailNotificationServiceImpl(
                    config =
                        EmailConfig(
                            resendApiKey = "re_test",
                            from = "Roadtrip Alerts <alerts@example.test>",
                            defaultTo = listOf("one@example.test"),
                        ),
                    client = client,
                )

            assertFalse(
                service.sendWatchOpenings(
                    watchId = 42L,
                    startDate = LocalDate.of(2026, 8, 1),
                    endDate = LocalDate.of(2026, 8, 3),
                    openings = listOf(opening()),
                ),
            )
        }

    @Test
    fun `sendWatchOpenings sends nothing when there are no openings`() =
        runBlocking {
            val client = RecordingEmailClient()
            val service =
                EmailNotificationServiceImpl(
                    config =
                        EmailConfig(
                            resendApiKey = "re_test",
                            from = "Roadtrip Alerts <alerts@example.test>",
                            defaultTo = listOf("one@example.test"),
                        ),
                    client = client,
                )

            assertFalse(
                service.sendWatchOpenings(
                    watchId = 42L,
                    startDate = LocalDate.of(2026, 8, 1),
                    endDate = LocalDate.of(2026, 8, 3),
                    openings = emptyList(),
                ),
            )
            assertTrue(client.messages.isEmpty())
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
