package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.service.notification.SlackNotificationService
import ca.floo.roadtrip.service.notification.WatchOpening
import ca.floo.roadtrip.service.notification.WatchStatusNotice
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriggerActionHandlerTest {
    @Test
    fun `known kind fires its handler`() =
        runBlocking {
            val fake = FakeHandler(kind = "slack_notify", result = true)
            val registry = TriggerActionRegistry(listOf(fake))

            val handler = registry.forKind("slack_notify")
            assertNotNull(handler)
            assertTrue(handler.fire(fakeWatch(id = 1L), openings = emptyList()))
            assertEquals(1, fake.calls)
        }

    @Test
    fun `unknown kind returns null and is inert`() {
        val registry = TriggerActionRegistry(listOf(FakeHandler(kind = "slack_notify")))
        // `atc` is the canonical unregistered kind today — no handler ⇒ inert.
        assertNull(registry.forKind("atc"))
        assertNull(registry.forKind("email"))
    }

    @Test
    fun `duplicate kinds in constructor throws`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                TriggerActionRegistry(listOf(FakeHandler(kind = "dup"), FakeHandler(kind = "dup")))
            }
        assertTrue(error.message!!.contains("duplicate handler kinds"))
    }

    @Test
    fun `SlackNotifyHandler forwards channel override to slack service`() =
        runBlocking {
            val slack = CapturingSlack(result = true)
            val handler = SlackNotifyHandler(slack = slack, appRootUrl = "https://app.example")

            val watch =
                fakeWatch(
                    id = 42L,
                    triggerConfig = JsonObject(mapOf("channel" to JsonPrimitive("custom-channel"))),
                )
            val delivered = handler.fire(watch, openings = listOf(anOpening()))

            assertTrue(delivered)
            assertEquals(42L, slack.lastWatchId)
            assertEquals("custom-channel", slack.lastChannel)
            assertEquals("https://app.example", slack.lastAppRootUrl)
        }

    @Test
    fun `SlackNotifyHandler omits channel when triggerConfig has none`() =
        runBlocking {
            val slack = CapturingSlack(result = true)
            val handler = SlackNotifyHandler(slack = slack, appRootUrl = null)

            handler.fire(fakeWatch(id = 7L), openings = listOf(anOpening()))

            // Null channel makes the service fall back to its configured default.
            assertNull(slack.lastChannel)
        }

    @Test
    fun `SlackNotifyHandler reports transport failure as false`() =
        runBlocking {
            // The dispatcher's "watch goes DONE only when fire() returns true"
            // gate is asserted at the dispatcher layer (AvailabilityPollExecutorTest
            // covers stopWhenTriggered against a failing Slack service); here we
            // verify the handler itself forwards the transport's success flag.
            val slack = CapturingSlack(result = false)
            val handler = SlackNotifyHandler(slack = slack, appRootUrl = null)

            assertFalse(handler.fire(fakeWatch(id = 9L), openings = listOf(anOpening())))
        }

    private class FakeHandler(
        override val kind: String,
        private val result: Boolean = true,
    ) : TriggerActionHandler {
        var calls: Int = 0

        override suspend fun fire(
            watch: AvailabilityWatchRepo.Watch,
            openings: List<WatchOpening>,
        ): Boolean {
            calls++
            return result
        }
    }

    /** [SlackNotificationService] double that records the last call to
     *  [sendWatchOpenings]; other methods no-op because the handler under test
     *  only exercises that one seam. */
    private class CapturingSlack(
        private val result: Boolean,
    ) : SlackNotificationService {
        var lastWatchId: Long? = null
        var lastChannel: String? = null
        var lastAppRootUrl: String? = null

        override suspend fun sendWatchOpenings(
            watchId: Long,
            startDate: LocalDate,
            endDate: LocalDate,
            openings: List<WatchOpening>,
            channel: String?,
            appRootUrl: String?,
        ): Boolean {
            lastWatchId = watchId
            lastChannel = channel
            lastAppRootUrl = appRootUrl
            return result
        }

        override suspend fun sendWatchStatus(
            notice: WatchStatusNotice,
            channel: String?,
        ): Boolean = result

        override suspend fun postResponseWatchStatus(
            responseUrl: String,
            notice: WatchStatusNotice,
        ): Boolean = result

        override suspend fun postResponseStaleWatch(
            responseUrl: String,
            watchId: Long,
        ): Boolean = result
    }

    private fun fakeWatch(
        id: Long,
        triggerConfig: JsonObject = JsonObject(emptyMap()),
    ): AvailabilityWatchRepo.Watch =
        AvailabilityWatchRepo.Watch(
            id = id,
            targets = emptyList<AvailabilityWatchTargetRepo.WatchTarget>(),
            campsiteFilters = JsonObject(emptyMap()),
            startDate = LocalDate.parse("2026-07-04"),
            endDate = LocalDate.parse("2026-07-06"),
            cadenceSec = null,
            triggerKinds = listOf(SlackNotifyHandler.KIND),
            triggerConfig = triggerConfig,
            stopWhenTriggered = false,
            status = WatchStatus.ACTIVE,
            createdAt = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
            updatedAt = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
        )

    private fun anOpening(): WatchOpening =
        WatchOpening(
            label = "Site 12",
            loop = "Loop A",
            siteType = "Tent",
            date = LocalDate.parse("2026-07-04"),
            campgroundId = 100L,
            campground = "Test CG",
            bookingUrl = null,
        )
}
