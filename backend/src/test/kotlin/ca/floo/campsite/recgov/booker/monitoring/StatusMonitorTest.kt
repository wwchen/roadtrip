package ca.floo.campsite.recgov.booker.monitoring

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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * StatusMonitor unit tests. We replace the real rec.gov probe with a
 * controllable stub so tests run fast and don't depend on the network.
 */
class StatusMonitorTest {
    @Test
    fun `bootstrap probe runs at start and seeds snapshot`() =
        runBlocking {
            val bus = EventBus(replay = 16)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val probeCount = AtomicInteger(0)
            val mon =
                StatusMonitor(
                    bus = bus,
                    scope = scope,
                    probe = {
                        probeCount.incrementAndGet()
                        true
                    },
                )
            mon.start()
            // Wait for the bootstrap probe.
            withTimeoutOrNull(2_000) {
                while (mon.snapshot() == null) delay(10)
            }
            scope.cancel()
            assertNotNull(mon.snapshot())
            assertTrue(mon.snapshot()!!.recgovReachable)
            assertEquals(1, probeCount.get())
        }

    @Test
    fun `failed PollDone triggers a probe`() =
        runBlocking {
            val bus = EventBus(replay = 16)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val probeCount = AtomicInteger(0)
            val mon =
                StatusMonitor(
                    bus = bus,
                    scope = scope,
                    probe = {
                        probeCount.incrementAndGet()
                        false
                    },
                    probeDebounce = Duration.ofMillis(50),
                )
            mon.start()
            // Wait for the bootstrap probe.
            withTimeoutOrNull(2_000) {
                while (mon.snapshot() == null) delay(10)
            }
            assertEquals(1, probeCount.get())
            // Wait past debounce, then publish a failed PollDone.
            delay(200)
            bus.publish(CampsiteEvent.PollDone(alertId = 1, success = false, endedAt = "now"))
            withTimeoutOrNull(2_000) {
                while (probeCount.get() < 2) delay(10)
            }
            scope.cancel()
            assertEquals(2, probeCount.get())
        }

    @Test
    fun `successful PollDone does not trigger a probe`() =
        runBlocking {
            val bus = EventBus(replay = 16)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val probeCount = AtomicInteger(0)
            val mon =
                StatusMonitor(
                    bus = bus,
                    scope = scope,
                    probe = {
                        probeCount.incrementAndGet()
                        true
                    },
                    probeDebounce = Duration.ofMillis(10),
                )
            mon.start()
            withTimeoutOrNull(2_000) { while (mon.snapshot() == null) delay(10) }
            delay(50)
            bus.publish(CampsiteEvent.PollDone(alertId = 1, success = true, endedAt = "now"))
            delay(200)
            scope.cancel()
            assertEquals(1, probeCount.get(), "expected only the bootstrap probe")
        }

    @Test
    fun `TokenRefreshFailed triggers a probe`() =
        runBlocking {
            val bus = EventBus(replay = 16)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val probeCount = AtomicInteger(0)
            val mon =
                StatusMonitor(
                    bus = bus,
                    scope = scope,
                    probe = {
                        probeCount.incrementAndGet()
                        true
                    },
                    probeDebounce = Duration.ofMillis(50),
                )
            mon.start()
            withTimeoutOrNull(2_000) { while (mon.snapshot() == null) delay(10) }
            delay(150)
            bus.publish(CampsiteEvent.TokenRefreshFailed(reason = "test"))
            withTimeoutOrNull(2_000) { while (probeCount.get() < 2) delay(10) }
            scope.cancel()
            assertEquals(2, probeCount.get())
        }

    @Test
    fun `degraded transition publishes RecgovDegraded`() =
        runBlocking {
            val bus = EventBus(replay = 16)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // Probe returns true once (bootstrap), then false (after PollDone failure).
            val probeResults = listOf(true, false).iterator()
            val mon =
                StatusMonitor(
                    bus = bus,
                    scope = scope,
                    probe = { probeResults.next() },
                    probeDebounce = Duration.ofMillis(50),
                )
            mon.start()
            withTimeoutOrNull(2_000) { while (mon.snapshot() == null) delay(10) }
            delay(150)
            bus.publish(CampsiteEvent.PollDone(alertId = 1, success = false, endedAt = "now"))
            withTimeoutOrNull(2_000) {
                bus.typedEvents.collect { env ->
                    if (env.event is CampsiteEvent.RecgovDegraded) return@collect
                }
            }
            scope.cancel()
            val degraded = bus.typedEvents.replayCache.any { it.event is CampsiteEvent.RecgovDegraded }
            assertTrue(degraded, "expected a RecgovDegraded event after probe flipped to false")
        }

    @Test
    fun `recovered transition publishes RecgovRecovered`() =
        runBlocking {
            val bus = EventBus(replay = 16)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val probeResults = listOf(false, true).iterator()
            val mon =
                StatusMonitor(
                    bus = bus,
                    scope = scope,
                    probe = { probeResults.next() },
                    probeDebounce = Duration.ofMillis(50),
                )
            mon.start()
            withTimeoutOrNull(2_000) { while (mon.snapshot() == null) delay(10) }
            delay(150)
            bus.publish(CampsiteEvent.PollDone(alertId = 1, success = false, endedAt = "now"))
            withTimeoutOrNull(2_000) {
                bus.typedEvents.collect { env ->
                    if (env.event is CampsiteEvent.RecgovRecovered) return@collect
                }
            }
            scope.cancel()
            val recovered = bus.typedEvents.replayCache.any { it.event is CampsiteEvent.RecgovRecovered }
            assertTrue(recovered)
        }

    @Test
    fun `debounced repeated failures do not re-probe`() =
        runBlocking {
            val bus = EventBus(replay = 16)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val probeCount = AtomicInteger(0)
            val mon =
                StatusMonitor(
                    bus = bus,
                    scope = scope,
                    probe = {
                        probeCount.incrementAndGet()
                        false
                    },
                    probeDebounce = Duration.ofSeconds(30),
                )
            mon.start()
            withTimeoutOrNull(2_000) { while (mon.snapshot() == null) delay(10) }
            // Hammer the bus with failures within the debounce window.
            repeat(5) { bus.publish(CampsiteEvent.PollDone(alertId = 1, success = false, endedAt = "now")) }
            delay(500)
            scope.cancel()
            // Bootstrap probe (1) only — no extra probes within debounce.
            assertEquals(1, probeCount.get())
        }
}
