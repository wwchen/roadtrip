package ca.floo.campsite.recgov.booker.monitoring

import ca.floo.campsite.recgov.booker.events.CampsiteEvent
import ca.floo.campsite.recgov.booker.events.EventBus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks recreation.gov reachability. Replaces the on-every-request probe
 * baked into [ca.floo.campsite.recgov.booker.api.StatusRoutes] with a
 * subscriber-driven model:
 *
 *   - At startup, probe once so the first /status call has a real answer.
 *   - On [CampsiteEvent.PollDone] with `success=false` → probe (debounced).
 *   - On [CampsiteEvent.TokenRefreshFailed] → probe (debounced).
 *   - When the probe result flips, publish [CampsiteEvent.RecgovDegraded]
 *     or [CampsiteEvent.RecgovRecovered] for SSE clients.
 *
 * `/api/campsite/status` reads [snapshot] — no I/O in the request path.
 */
class StatusMonitor(
    private val bus: EventBus,
    private val scope: CoroutineScope,
    private val probe: suspend () -> Boolean = ::defaultProbe,
    private val probeDebounce: Duration = Duration.ofSeconds(30),
) {
    data class Snapshot(
        val recgovReachable: Boolean,
        val checkedAt: Instant,
    )

    private val log = LoggerFactory.getLogger(StatusMonitor::class.java)
    private val state = AtomicReference<Snapshot?>(null)
    private val probeMutex = Mutex()
    private val lastProbeAt = AtomicReference<Instant?>(null)

    fun start() {
        // Bootstrap: probe once so the first /status request returns real data.
        scope.launch {
            runProbe(reason = "startup")
        }
        scope.launch {
            bus.typedEvents.collect { env ->
                when (val ev = env.event) {
                    is CampsiteEvent.PollDone ->
                        if (!ev.success) maybeProbe(reason = "poll_done.failure")
                    is CampsiteEvent.TokenRefreshFailed -> maybeProbe(reason = "token_refresh_failed")
                    else -> Unit
                }
            }
        }
    }

    /** Last-known reachability + when we measured it. Null until the bootstrap probe completes. */
    fun snapshot(): Snapshot? = state.get()

    /** Maybe-probe gated on the debounce window. Skips if a probe ran within [probeDebounce]. */
    private suspend fun maybeProbe(reason: String) {
        val last = lastProbeAt.get()
        if (last != null && Duration.between(last, Instant.now()) < probeDebounce) {
            log.debug("StatusMonitor: skipping probe ({}), last ran {} ago", reason, Duration.between(last, Instant.now()))
            return
        }
        runProbe(reason)
    }

    private suspend fun runProbe(reason: String) {
        if (!probeMutex.tryLock()) return
        try {
            log.info("StatusMonitor: probing rec.gov reachability (reason={})", reason)
            val now = Instant.now()
            val ok =
                runCatching { probe() }
                    .onFailure { log.info("StatusMonitor: probe threw — {}", it.message) }
                    .getOrDefault(false)
            val prior = state.getAndSet(Snapshot(ok, now))
            lastProbeAt.set(now)

            // Only emit transition events on a flip.
            if (prior == null || prior.recgovReachable != ok) {
                if (ok) {
                    bus.publish(CampsiteEvent.RecgovRecovered(checkedAt = now.toString()))
                } else {
                    bus.publish(CampsiteEvent.RecgovDegraded(reason = reason))
                }
            }
        } finally {
            probeMutex.unlock()
        }
    }

    companion object {
        private val httpClient by lazy { HttpClient(CIO) { engine { requestTimeout = 6_000 } } }

        /** Default reachability probe: GET https://www.recreation.gov/ with a benign UA, 6s timeout. */
        suspend fun defaultProbe(): Boolean =
            runCatching {
                httpClient
                    .get("https://www.recreation.gov/") { header("User-Agent", "Mozilla/5.0") }
                    .status
                    .value in 200..399
            }.getOrDefault(false)
    }
}
