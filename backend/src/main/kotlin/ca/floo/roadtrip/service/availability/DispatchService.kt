package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.service.notification.SlackNotificationService
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

private const val DEFAULT_PENDING_TTL_SECONDS = 30L
private const val MAX_CLAIM_WAIT_SECONDS = 30L
private const val MIN_CLAIM_WAIT_MILLIS = 1L
private const val DEFAULT_LEASE_SECONDS = 30L
private const val MIN_LEASE_SECONDS = 1L
private const val MAX_LEASE_SECONDS = 120L
private const val TEST_OPENING_LABEL = "Companion Test Site"
private const val TEST_OPENING_CAMPGROUND = "Companion Test Campground"
private const val TEST_BOOKING_URL = "https://example.invalid/companion-test"
private const val TEST_WATCH_ID = 0L
private const val TEST_WINDOW_DAYS = 1L
private const val DISPATCH_RESULT_COMPLETED = "completed"
private const val DISPATCH_RESULT_FAILED = "failed"

internal fun interface DispatchWatchCompletion {
    fun markDone(watchId: Long): Boolean
}

internal class DispatchService(
    private val store: DispatchStore,
    private val waiters: DispatchWaiterRegistry,
    private val slack: SlackNotificationService,
    private val watchCompletion: DispatchWatchCompletion,
    private val clock: Clock = Clock.systemUTC(),
    private val pendingTtl: Duration = Duration.ofSeconds(DEFAULT_PENDING_TTL_SECONDS),
) : DispatchEnqueuer {
    suspend fun claim(
        selector: DispatchClaimSelector,
        wait: Duration,
        lease: Duration,
    ): DispatchClaimed? {
        val leaseDuration = normalizeLease(lease)
        store.claim(selector, leaseDuration, now())?.let { return it }
        val waitDuration = normalizeWait(wait)
        if (waitDuration.isZero) return null

        val registration = waiters.register(selector)
        try {
            store.claim(selector, leaseDuration, now())?.let { return it }
            withTimeoutOrNull(waitDuration.toMillis().coerceAtLeast(MIN_CLAIM_WAIT_MILLIS)) {
                registration.await()
            }
            return store.claim(selector, leaseDuration, now())
        } finally {
            registration.close()
        }
    }

    fun heartbeat(
        id: Long,
        leaseToken: String,
        lease: Duration,
    ): DispatchLeaseResult = store.heartbeat(id, leaseToken, normalizeLease(lease), now())

    suspend fun complete(
        id: Long,
        leaseToken: String,
        request: JsonObject,
    ): DispatchCompleteOutcome =
        when (val result = store.complete(id, leaseToken, now())) {
            is DispatchCompleteResult.Completed -> {
                val dispatch = result.dispatch
                slack.sendDispatchResult(
                    dispatchId = dispatch.id,
                    kind = dispatch.kind,
                    vendor = dispatch.vendor,
                    payloadVersion = dispatch.payloadVersion,
                    status = DISPATCH_RESULT_COMPLETED,
                    request = request,
                )
                val watchDone =
                    dispatch.watchId
                        ?.takeIf { dispatch.stopWhenTriggered }
                        ?.let { watchCompletion.markDone(it) }
                DispatchCompleteOutcome.Completed(id = dispatch.id, watchDone = watchDone)
            }
            DispatchCompleteResult.InvalidLease -> DispatchCompleteOutcome.InvalidLease
            DispatchCompleteResult.NotFound -> DispatchCompleteOutcome.NotFound
        }

    suspend fun fail(
        id: Long,
        leaseToken: String,
        request: JsonObject,
    ): DispatchFailResult =
        when (val result = store.fail(id, leaseToken, now())) {
            is DispatchFailResult.Failed -> {
                val dispatch = result.dispatch
                slack.sendDispatchResult(
                    dispatchId = dispatch.id,
                    kind = dispatch.kind,
                    vendor = dispatch.vendor,
                    payloadVersion = dispatch.payloadVersion,
                    status = DISPATCH_RESULT_FAILED,
                    request = request,
                )
                result
            }
            DispatchFailResult.InvalidLease -> DispatchFailResult.InvalidLease
            DispatchFailResult.NotFound -> DispatchFailResult.NotFound
        }

    suspend fun enqueueTestEvent(
        kind: String,
        vendor: String,
        simulateResult: String,
        payloadVersion: String?,
        payload: JsonObject,
        watchId: Long?,
        stopWhenTriggered: Boolean,
    ): DispatchQueued {
        val normalizedKind = normalizeDispatchKey(kind)
        val normalizedVendor = normalizeDispatchKey(vendor)
        val version = payloadVersion?.let(::normalizeDispatchKey) ?: dispatchPayloadVersion(AtcTriggerActionHandler.KIND, normalizedVendor)
        return enqueue(
            input =
                DispatchCreateInput(
                    kind = normalizedKind,
                    vendor = normalizedVendor,
                    payloadVersion = version,
                    payload = testPayload(normalizedVendor, version, simulateResult, payload, watchId),
                    watchId = watchId,
                    stopWhenTriggered = stopWhenTriggered,
                ),
        )
    }

    override suspend fun enqueue(input: DispatchCreateInput): DispatchQueued {
        val queued = store.enqueue(input, pendingTtl, now())
        val notifiedWaiters = waiters.notifyMatching(queued)
        return queued.copy(notifiedWaiters = notifiedWaiters)
    }

    private fun testPayload(
        vendor: String,
        payloadVersion: String,
        simulateResult: String,
        payload: JsonObject,
        watchId: Long?,
    ): JsonObject {
        val startDate = LocalDate.ofInstant(now(), ZoneOffset.UTC)
        val endDate = startDate.plusDays(TEST_WINDOW_DAYS)
        return buildJsonObject {
            put("watch_id", watchId ?: TEST_WATCH_ID)
            put("vendor", vendor)
            put("payload_version", payloadVersion)
            put("start_date", startDate.toString())
            put("end_date", endDate.toString())
            putJsonArray("openings") {
                add(testOpeningPayload(vendor, startDate))
            }
            put("simulate_result", normalizeDispatchKey(simulateResult))
            if (payload.isNotEmpty()) put("data", payload)
        }
    }

    private fun testOpeningPayload(
        vendor: String,
        date: LocalDate,
    ): JsonObject =
        buildJsonObject {
            put("label", TEST_OPENING_LABEL)
            put("date", date.toString())
            put("vendor", vendor)
            put("site_type", "test")
            put("campground", TEST_OPENING_CAMPGROUND)
            put("booking_url", TEST_BOOKING_URL)
        }

    private fun normalizeWait(wait: Duration): Duration =
        when {
            wait.isNegative || wait.isZero -> Duration.ZERO
            wait.seconds > MAX_CLAIM_WAIT_SECONDS -> Duration.ofSeconds(MAX_CLAIM_WAIT_SECONDS)
            else -> wait
        }

    private fun normalizeLease(lease: Duration): Duration =
        when {
            lease.seconds < MIN_LEASE_SECONDS -> Duration.ofSeconds(DEFAULT_LEASE_SECONDS)
            lease.seconds > MAX_LEASE_SECONDS -> Duration.ofSeconds(MAX_LEASE_SECONDS)
            else -> lease
        }

    private fun now() = clock.instant()
}

internal sealed class DispatchCompleteOutcome {
    data class Completed(
        val id: Long,
        val watchDone: Boolean?,
    ) : DispatchCompleteOutcome()

    data object NotFound : DispatchCompleteOutcome()

    data object InvalidLease : DispatchCompleteOutcome()
}
