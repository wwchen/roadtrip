package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.DispatchConfig
import ca.floo.roadtrip.service.notification.SlackNotificationService
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.time.Duration

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
    private val config: DispatchConfig,
    private val clock: Clock = Clock.systemUTC(),
) : DispatchEnqueuer {
    suspend fun claim(
        selector: DispatchClaimSelector,
        wait: Duration?,
        lease: Duration?,
    ): DispatchClaimed? {
        val leaseDuration = normalizeLease(lease)
        store.claim(selector, leaseDuration, now())?.let { return it }
        val waitDuration = normalizeWait(wait)
        if (waitDuration.isZero) return null

        val registration = waiters.register(selector)
        try {
            store.claim(selector, leaseDuration, now())?.let { return it }
            withTimeoutOrNull(waitDuration.toMillis().coerceAtLeast(config.minClaimWait.toMillis())) {
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
        lease: Duration?,
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

    override suspend fun enqueue(input: DispatchCreateInput): DispatchQueued {
        val queued = store.enqueue(input, config.pendingTtl, now())
        val notifiedWaiters = waiters.notifyMatching(queued)
        return queued.copy(notifiedWaiters = notifiedWaiters)
    }

    private fun normalizeWait(wait: Duration?): Duration =
        when {
            wait == null -> config.maxClaimWait
            wait.isNegative || wait.isZero -> Duration.ZERO
            wait > config.maxClaimWait -> config.maxClaimWait
            else -> wait
        }

    private fun normalizeLease(lease: Duration?): Duration =
        when {
            lease == null -> config.defaultLease
            lease < config.minLease -> config.defaultLease
            lease > config.maxLease -> config.maxLease
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
