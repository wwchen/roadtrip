package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.service.availability.WatchStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlackInteractivityHandlerTest {
    private val responseUrl = "https://hooks.slack.test/actions/xyz"

    /** Fake watch layer: records what the handler asked for, returns fixed
     *  fixtures. Test bodies configure a specific fixture per case rather than
     *  driving a real repo through jOOQ. */
    private class FakeWatches(
        private val watches: Map<Long, AvailabilityWatchRepo.Watch>,
    ) : SlackInteractivityHandler.Watches {
        val statusCalls = mutableListOf<Pair<Long, WatchStatus>>()
        val deleteCalls = mutableListOf<Long>()
        val noticeCalls = mutableListOf<Pair<Long, WatchStatusNotice.State>>()

        override fun setStatus(
            id: Long,
            status: WatchStatus,
        ): AvailabilityWatchRepo.Watch? {
            statusCalls += id to status
            val existing = watches[id] ?: return null
            return existing.copy(status = status)
        }

        override fun snapshotAndDelete(id: Long): AvailabilityWatchRepo.Watch? {
            deleteCalls += id
            return watches[id]
        }

        override fun buildStatusNotice(
            watch: AvailabilityWatchRepo.Watch,
            state: WatchStatusNotice.State,
        ): WatchStatusNotice {
            noticeCalls += watch.id to state
            return WatchStatusNotice(
                watchId = watch.id,
                state = state,
                siteCount = 1,
                siteName = "site",
                siteLoop = null,
                campgroundName = null,
                startDate = watch.startDate,
                endDate = watch.endDate,
                dashboardUrl = null,
                poiLinks = emptyList(),
            )
        }
    }

    /** Fake Slack transport — tests only need to observe what got posted to
     *  which response_url; the render itself is covered by the renderer test. */
    private class FakeSlack : SlackNotificationService {
        data class Response(
            val url: String,
            val notice: WatchStatusNotice,
        )

        val responses = mutableListOf<Response>()

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
        ): Boolean {
            responses += Response(responseUrl, notice)
            return true
        }
    }

    private fun watch(
        id: Long = 42,
        status: WatchStatus = WatchStatus.ACTIVE,
    ) = AvailabilityWatchRepo.Watch(
        id = id,
        targets = listOf(AvailabilityWatchTargetRepo.WatchTarget(id = 100, watchId = id, poiId = 7, reservableId = null)),
        reservableFilters = JsonObject(emptyMap()),
        startDate = LocalDate.of(2026, 7, 11),
        endDate = LocalDate.of(2026, 7, 12),
        cadenceSec = null,
        triggerKinds = listOf("slack_notify"),
        triggerConfig = JsonObject(emptyMap()),
        stopWhenTriggered = false,
        status = status,
        createdAt = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
        updatedAt = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
    )

    private fun payload(
        actionId: String,
        value: String? = "42",
    ) = BlockActionsPayload(
        type = "block_actions",
        actions = listOf(BlockAction(actionId = actionId, value = value)),
        responseUrl = responseUrl,
    )

    @Test
    fun `pause action flips status to PAUSED and posts a PAUSED card via response_url`() =
        runBlocking {
            val fakes = FakeWatches(mapOf(42L to watch()))
            val slack = FakeSlack()
            SlackInteractivityHandler(fakes, slack).handle(payload(SlackWatchCard.ACTION_WATCH_PAUSE))

            assertEquals(listOf(42L to WatchStatus.PAUSED), fakes.statusCalls)
            assertEquals(listOf(42L to WatchStatusNotice.State.PAUSED), fakes.noticeCalls)
            val resp = slack.responses.single()
            assertEquals(responseUrl, resp.url)
            assertEquals(WatchStatusNotice.State.PAUSED, resp.notice.state)
        }

    @Test
    fun `resume action flips status to ACTIVE and posts a WATCHING card`() =
        runBlocking {
            val fakes = FakeWatches(mapOf(42L to watch(status = WatchStatus.PAUSED)))
            val slack = FakeSlack()
            SlackInteractivityHandler(fakes, slack).handle(payload(SlackWatchCard.ACTION_WATCH_RESUME))

            assertEquals(listOf(42L to WatchStatus.ACTIVE), fakes.statusCalls)
            assertEquals(
                WatchStatusNotice.State.WATCHING,
                slack.responses
                    .single()
                    .notice.state,
            )
        }

    @Test
    fun `delete action snapshots + deletes and posts a STOPPED card`() =
        runBlocking {
            val fakes = FakeWatches(mapOf(42L to watch()))
            val slack = FakeSlack()
            SlackInteractivityHandler(fakes, slack).handle(payload(SlackWatchCard.ACTION_WATCH_DELETE))

            assertEquals(listOf(42L), fakes.deleteCalls)
            assertEquals(
                WatchStatusNotice.State.STOPPED,
                slack.responses
                    .single()
                    .notice.state,
            )
        }

    @Test
    fun `URL-button follow-ups (Reserve, Grid, Map, Dashboard) silently ack — no mutation, no response_url post`() =
        runBlocking {
            val fakes = FakeWatches(mapOf(42L to watch()))
            val slack = FakeSlack()
            val h = SlackInteractivityHandler(fakes, slack)
            listOf(
                SlackWatchCard.ACTION_RESERVE_SITE,
                SlackWatchCard.ACTION_OPEN_GRID,
                SlackWatchCard.ACTION_OPEN_MAP,
                SlackWatchCard.ACTION_OPEN_DASHBOARD,
            ).forEach { h.handle(payload(it)) }

            // The redirect already happened client-side; the endpoint must not
            // mutate the watch or push a spurious in-place update.
            assertTrue(fakes.statusCalls.isEmpty())
            assertTrue(fakes.deleteCalls.isEmpty())
            assertTrue(slack.responses.isEmpty())
        }

    @Test
    fun `an unknown action_id is ignored (logged) without touching the watch or Slack`() =
        runBlocking {
            val fakes = FakeWatches(mapOf(42L to watch()))
            val slack = FakeSlack()
            SlackInteractivityHandler(fakes, slack).handle(payload("watch_extend")) // not in the shipped set

            assertTrue(fakes.statusCalls.isEmpty())
            assertTrue(slack.responses.isEmpty())
        }

    @Test
    fun `a stale card whose watch is gone drops the mutation without a Slack post`() =
        runBlocking {
            val fakes = FakeWatches(emptyMap()) // watch 42 already deleted
            val slack = FakeSlack()
            SlackInteractivityHandler(fakes, slack).handle(payload(SlackWatchCard.ACTION_WATCH_PAUSE))

            // FakeWatches records the call for observability, but setStatus
            // returned null, so no response_url post fires.
            assertEquals(listOf(42L to WatchStatus.PAUSED), fakes.statusCalls)
            assertTrue(slack.responses.isEmpty())
        }

    @Test
    fun `a non-numeric value drops the action safely — no mutation attempt`() =
        runBlocking {
            val fakes = FakeWatches(mapOf(42L to watch()))
            val slack = FakeSlack()
            SlackInteractivityHandler(fakes, slack).handle(payload(SlackWatchCard.ACTION_WATCH_PAUSE, value = "not-a-number"))

            assertTrue(fakes.statusCalls.isEmpty(), "handler must not attempt setStatus with a bad id")
            assertTrue(slack.responses.isEmpty())
        }

    @Test
    fun `parse extracts the first action id and value from a block_actions payload`() {
        val json =
            """
            {
              "type": "block_actions",
              "response_url": "https://hooks.slack.test/x",
              "actions": [
                { "action_id": "watch_pause", "value": "42", "type": "button" }
              ]
            }
            """.trimIndent()

        val payload = SlackInteractivityHandler.parse(json)
        assertNotNull(payload)
        assertEquals("watch_pause", payload.actions.single().actionId)
        assertEquals("42", payload.actions.single().value)
        assertEquals("https://hooks.slack.test/x", payload.responseUrl)
    }

    @Test
    fun `parse tolerates unknown fields and returns null on garbage`() {
        val ok =
            SlackInteractivityHandler.parse(
                """{"type":"block_actions","team":{"id":"T1"},"actions":[{"action_id":"a","type":"button"}]}""",
            )
        assertNotNull(ok)
        assertEquals("a", ok.actions.single().actionId)

        assertNull(SlackInteractivityHandler.parse("not json"))
    }
}
