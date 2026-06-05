package ca.floo.campsite.recgov.booker.scheduler

import ca.floo.campsite.recgov.booker.db.Schedule
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the Scheduler turns supplied schedule rows + alert cadences
 * into typed event publishes on the EventBus, and that reload semantics
 * (add/remove jobs) work. The full schedule + alert path through Postgres
 * is not covered here — Scheduler is decoupled from the repos via two
 * lambdas, so unit tests pass plain lists.
 */
class SchedulerTest {
    @Test
    fun `schedule rows publish their event types on cadence`() =
        runBlocking {
            val bus = EventBus(replay = 32)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val rows =
                listOf(
                    Schedule(id = 1, name = "lease_sweep", eventType = "LeaseSweepDue", payloadJson = "{}", cadenceSec = 1, enabled = true),
                    Schedule(
                        id = 2,
                        name = "liveness_tick",
                        eventType = "LivenessTick",
                        payloadJson = "{}",
                        cadenceSec = 1,
                        enabled = true,
                    ),
                )
            val scheduler = Scheduler({ rows }, { emptyList() }, bus, scope)
            scheduler.start()

            val seen = mutableSetOf<Class<*>>()
            withTimeoutOrNull(3_500) {
                bus.typedEvents.collect { env ->
                    if (env.event is CampsiteEvent.LeaseSweepDue) seen += CampsiteEvent.LeaseSweepDue::class.java
                    if (env.event is CampsiteEvent.LivenessTick) seen += CampsiteEvent.LivenessTick::class.java
                }
            }
            scheduler.stop()
            scope.cancel()
            assertTrue(seen.contains(CampsiteEvent.LeaseSweepDue::class.java), "missing LeaseSweepDue: $seen")
            assertTrue(seen.contains(CampsiteEvent.LivenessTick::class.java), "missing LivenessTick: $seen")
        }

    @Test
    fun `per-alert cadence rows produce PollDue events`() =
        runBlocking {
            val bus = EventBus(replay = 32)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val cadences = listOf(7L to 1, 9L to 1)
            val scheduler = Scheduler({ emptyList() }, { cadences }, bus, scope)
            scheduler.start()

            val seen = mutableSetOf<Long>()
            withTimeoutOrNull(3_500) {
                bus.typedEvents.collect { env ->
                    val ev = env.event
                    if (ev is CampsiteEvent.PollDue) {
                        seen += ev.alertId
                        if (seen.size == 2) return@collect
                    }
                }
            }
            scheduler.stop()
            scope.cancel()
            assertEquals(setOf(7L, 9L), seen)
        }

    @Test
    fun `removeAlert stops further PollDue events for that alert`() =
        runBlocking {
            val bus = EventBus(replay = 64)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val scheduler = Scheduler({ emptyList() }, { listOf(42L to 1) }, bus, scope)
            scheduler.start()

            // Wait for at least one PollDue(42).
            withTimeoutOrNull(3_000) {
                bus.typedEvents.collect { env ->
                    val ev = env.event
                    if (ev is CampsiteEvent.PollDue && ev.alertId == 42L) return@collect
                }
            }
            scheduler.removeAlert(42)
            val countBefore = bus.typedEvents.replayCache.count { (it.event as? CampsiteEvent.PollDue)?.alertId == 42L }
            // Quiet window — no more should arrive (allow 1 in-flight at the moment of cancellation).
            delay(1_500)
            val countAfter = bus.typedEvents.replayCache.count { (it.event as? CampsiteEvent.PollDue)?.alertId == 42L }
            scheduler.stop()
            scope.cancel()
            assertTrue(countAfter - countBefore <= 1, "expected 0..1 in-flight PollDue post-removal, got delta=${countAfter - countBefore}")
        }
}
