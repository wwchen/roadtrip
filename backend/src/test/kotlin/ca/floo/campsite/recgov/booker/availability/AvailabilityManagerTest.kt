package ca.floo.campsite.recgov.booker.availability

import ca.floo.campsite.recgov.booker.domain.Alert
import ca.floo.campsite.recgov.booker.events.CampsiteEvent
import ca.floo.campsite.recgov.booker.events.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for AvailabilityManager mutex + debounce + dispatch behavior.
 * The actual rec.gov check body is replaced with an in-process counter so
 * tests don't need network or a real DSLContext.
 */
class AvailabilityManagerTest {
    private fun activeAlert(
        id: Long,
        cadence: Int = 60,
    ) = Alert(
        id = id,
        campgroundId = "cg-$id",
        campgroundName = "Test $id",
        startDate = "2026-08-01",
        endDate = "2026-08-05",
        minNights = 1,
        status = "active",
        createdAt = "2026-06-05T00:00:00Z",
    )

    @Test
    fun `PollDue triggers a check that publishes PollDone`() =
        runBlocking {
            val bus = EventBus(replay = 32)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val checks = AtomicInteger(0)
            val mgr =
                AvailabilityManager(
                    getAlert = { activeAlert(it) },
                    listActiveAlerts = { listOf(activeAlert(1)) },
                    checkAlertNow = {
                        checks.incrementAndGet()
                        AvailabilityManager.CheckResult(emptyList(), success = true)
                    },
                    bus = bus,
                    scope = scope,
                )
            mgr.start()

            bus.publish(CampsiteEvent.PollDue(alertId = 7))

            withTimeoutOrNull(2_000) {
                bus.typedEvents.collect { env ->
                    val ev = env.event
                    if (ev is CampsiteEvent.PollDone && ev.alertId == 7L) return@collect
                }
            }
            scope.cancel()
            assertEquals(1, checks.get())
        }

    @Test
    fun `back-to-back PollDue for the same alert is debounced`() =
        runBlocking {
            val bus = EventBus(replay = 32)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val checks = AtomicInteger(0)
            val mgr =
                AvailabilityManager(
                    getAlert = { activeAlert(it) },
                    listActiveAlerts = { emptyList() },
                    checkAlertNow = {
                        checks.incrementAndGet()
                        AvailabilityManager.CheckResult(emptyList(), success = true)
                    },
                    bus = bus,
                    scope = scope,
                    debounce = Duration.ofSeconds(30),
                )
            mgr.start()

            bus.publish(CampsiteEvent.PollDue(alertId = 7))
            // Wait for first poll to complete.
            withTimeoutOrNull(2_000) {
                bus.typedEvents.collect { env ->
                    val ev = env.event
                    if (ev is CampsiteEvent.PollDone && ev.alertId == 7L) return@collect
                }
            }
            // Fire a second PollDue immediately — debounce should suppress.
            bus.publish(CampsiteEvent.PollDue(alertId = 7))
            delay(500)
            scope.cancel()
            assertEquals(1, checks.get(), "expected only the first poll, got ${checks.get()}")
        }

    @Test
    fun `UserPolledNow forces a check past the debounce window`() =
        runBlocking {
            val bus = EventBus(replay = 32)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val checks = AtomicInteger(0)
            val mgr =
                AvailabilityManager(
                    getAlert = { activeAlert(it) },
                    listActiveAlerts = { emptyList() },
                    checkAlertNow = {
                        checks.incrementAndGet()
                        AvailabilityManager.CheckResult(emptyList(), success = true)
                    },
                    bus = bus,
                    scope = scope,
                    debounce = Duration.ofSeconds(30),
                )
            mgr.start()

            bus.publish(CampsiteEvent.PollDue(alertId = 7))
            withTimeoutOrNull(2_000) {
                bus.typedEvents.collect { env ->
                    val ev = env.event
                    if (ev is CampsiteEvent.PollDone && ev.alertId == 7L) return@collect
                }
            }
            // Now user clicks "check now" — bypasses debounce.
            bus.publish(CampsiteEvent.UserPolledNow(alertId = 7))
            withTimeoutOrNull(2_000) {
                var seen = 0
                bus.typedEvents.collect { env ->
                    val ev = env.event
                    if (ev is CampsiteEvent.PollDone && ev.alertId == 7L) {
                        seen++
                        if (seen == 2) return@collect
                    }
                }
            }
            scope.cancel()
            assertEquals(2, checks.get())
        }

    @Test
    fun `MatchFound is published for each new match envelope`() =
        runBlocking {
            val bus = EventBus(replay = 32)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val mgr =
                AvailabilityManager(
                    getAlert = { activeAlert(it) },
                    listActiveAlerts = { emptyList() },
                    checkAlertNow = {
                        AvailabilityManager.CheckResult(
                            newMatchEnvelopes = listOf("""{"id":1}""", """{"id":2}"""),
                            success = true,
                        )
                    },
                    bus = bus,
                    scope = scope,
                )
            mgr.start()
            bus.publish(CampsiteEvent.UserPolledNow(alertId = 7))

            withTimeoutOrNull(2_000) {
                bus.typedEvents.collect { env ->
                    val ev = env.event
                    if (ev is CampsiteEvent.PollDone && ev.alertId == 7L) return@collect
                }
            }
            scope.cancel()
            val matchEvents = bus.typedEvents.replayCache.count { it.event is CampsiteEvent.MatchFound }
            assertEquals(2, matchEvents)
        }

    @Test
    fun `non-active alert is skipped`() =
        runBlocking {
            val bus = EventBus(replay = 32)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val checks = AtomicInteger(0)
            val mgr =
                AvailabilityManager(
                    getAlert = { activeAlert(it).copy(status = "paused") },
                    listActiveAlerts = { emptyList() },
                    checkAlertNow = {
                        checks.incrementAndGet()
                        AvailabilityManager.CheckResult(emptyList(), success = true)
                    },
                    bus = bus,
                    scope = scope,
                )
            mgr.start()
            bus.publish(CampsiteEvent.UserPolledNow(alertId = 7))
            delay(500)
            scope.cancel()
            assertEquals(0, checks.get())
        }

    @Test
    fun `failed check publishes PollDone with success=false`() =
        runBlocking {
            val bus = EventBus(replay = 32)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val mgr =
                AvailabilityManager(
                    getAlert = { activeAlert(it) },
                    listActiveAlerts = { emptyList() },
                    checkAlertNow = {
                        AvailabilityManager.CheckResult(emptyList(), success = false, error = "boom")
                    },
                    bus = bus,
                    scope = scope,
                )
            mgr.start()
            bus.publish(CampsiteEvent.UserPolledNow(alertId = 7))
            withTimeoutOrNull(2_000) {
                bus.typedEvents.collect { env ->
                    val ev = env.event
                    if (ev is CampsiteEvent.PollDone && ev.alertId == 7L) return@collect
                }
            }
            scope.cancel()
            val pollDone = bus.typedEvents.replayCache.lastOrNull { (it.event as? CampsiteEvent.PollDone)?.alertId == 7L }
            assertTrue(pollDone != null && (pollDone.event as CampsiteEvent.PollDone).success.not())
        }
}
