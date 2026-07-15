package ca.floo.roadtrip.service.availability

import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private enum class DispatchStatus {
    PENDING,
    CLAIMED,
}

internal class InMemoryDispatchStore : DispatchStore {
    private val lock = Any()
    private val ids = AtomicLong()
    private val dispatches = LinkedHashMap<Long, StoredDispatch>()

    override fun enqueue(
        input: DispatchCreateInput,
        pendingTtl: Duration,
        now: Instant,
    ): DispatchQueued =
        synchronized(lock) {
            pruneExpiredLocked(now)
            val id = ids.incrementAndGet()
            val stored =
                StoredDispatch(
                    id = id,
                    kind = normalizeDispatchKey(input.kind),
                    vendor = normalizeDispatchKey(input.vendor),
                    payloadVersion = normalizeDispatchKey(input.payloadVersion),
                    payload = input.payload,
                    watchId = input.watchId,
                    stopWhenTriggered = input.stopWhenTriggered,
                    pendingExpiresAt = now.plus(pendingTtl),
                )
            dispatches[id] = stored
            stored.toQueued(notifiedWaiters = 0)
        }

    override fun claim(
        selector: DispatchClaimSelector,
        leaseDuration: Duration,
        now: Instant,
    ): DispatchClaimed? =
        synchronized(lock) {
            pruneExpiredLocked(now)
            val entry =
                dispatches.entries.firstOrNull { (_, dispatch) ->
                    dispatch.status == DispatchStatus.PENDING &&
                        selector.matches(dispatch.kind, dispatch.vendor, dispatch.payloadVersion)
                } ?: return@synchronized null
            val leaseToken = UUID.randomUUID().toString()
            val leaseExpiresAt = now.plus(leaseDuration)
            val claimed = entry.value.copy(status = DispatchStatus.CLAIMED, leaseToken = leaseToken, leaseExpiresAt = leaseExpiresAt)
            dispatches[entry.key] = claimed
            claimed.toClaimed(leaseToken, leaseExpiresAt)
        }

    override fun heartbeat(
        id: Long,
        leaseToken: String,
        leaseDuration: Duration,
        now: Instant,
    ): DispatchLeaseResult =
        synchronized(lock) {
            val dispatch = dispatches[id] ?: return@synchronized DispatchLeaseResult.NotFound
            val valid = dispatch.hasValidLease(leaseToken, now)
            if (!valid) {
                releaseExpiredLeaseLocked(dispatch, now)
                return@synchronized DispatchLeaseResult.InvalidLease
            }
            val leaseExpiresAt = now.plus(leaseDuration)
            dispatches[id] = dispatch.copy(leaseExpiresAt = leaseExpiresAt)
            DispatchLeaseResult.Updated(id = id, leaseExpiresAt = leaseExpiresAt)
        }

    override fun complete(
        id: Long,
        leaseToken: String,
        now: Instant,
    ): DispatchCompleteResult =
        synchronized(lock) {
            val dispatch = dispatches[id] ?: return@synchronized DispatchCompleteResult.NotFound
            val valid = dispatch.hasValidLease(leaseToken, now)
            if (!valid) {
                releaseExpiredLeaseLocked(dispatch, now)
                return@synchronized DispatchCompleteResult.InvalidLease
            }
            dispatches.remove(id)
            DispatchCompleteResult.Completed(
                DispatchCompleted(
                    id = dispatch.id,
                    kind = dispatch.kind,
                    vendor = dispatch.vendor,
                    payloadVersion = dispatch.payloadVersion,
                    payload = dispatch.payload,
                    watchId = dispatch.watchId,
                    stopWhenTriggered = dispatch.stopWhenTriggered,
                ),
            )
        }

    override fun fail(
        id: Long,
        leaseToken: String,
        now: Instant,
    ): DispatchFailResult =
        synchronized(lock) {
            val dispatch = dispatches[id] ?: return@synchronized DispatchFailResult.NotFound
            val valid = dispatch.hasValidLease(leaseToken, now)
            if (!valid) {
                releaseExpiredLeaseLocked(dispatch, now)
                return@synchronized DispatchFailResult.InvalidLease
            }
            dispatches.remove(id)
            DispatchFailResult.Failed(
                DispatchFailed(
                    id = dispatch.id,
                    kind = dispatch.kind,
                    vendor = dispatch.vendor,
                    payloadVersion = dispatch.payloadVersion,
                    payload = dispatch.payload,
                ),
            )
        }

    private fun pruneExpiredLocked(now: Instant) {
        val iterator = dispatches.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val dispatch = entry.value
            when {
                dispatch.status == DispatchStatus.PENDING && !dispatch.pendingExpiresAt.isAfter(now) -> iterator.remove()
                dispatch.status == DispatchStatus.CLAIMED && dispatch.leaseExpiresAt?.isAfter(now) == false ->
                    if (dispatch.pendingExpiresAt.isAfter(now)) {
                        entry.setValue(dispatch.released())
                    } else {
                        iterator.remove()
                    }
            }
        }
    }

    private fun releaseExpiredLeaseLocked(
        dispatch: StoredDispatch,
        now: Instant,
    ) {
        if (dispatch.status != DispatchStatus.CLAIMED || dispatch.leaseExpiresAt?.isAfter(now) == true) return
        if (dispatch.pendingExpiresAt.isAfter(now)) {
            dispatches[dispatch.id] = dispatch.released()
        } else {
            dispatches.remove(dispatch.id)
        }
    }

    private data class StoredDispatch(
        val id: Long,
        val kind: String,
        val vendor: String,
        val payloadVersion: String,
        val payload: JsonObject,
        val watchId: Long?,
        val stopWhenTriggered: Boolean,
        val pendingExpiresAt: Instant,
        val status: DispatchStatus = DispatchStatus.PENDING,
        val leaseToken: String? = null,
        val leaseExpiresAt: Instant? = null,
    ) {
        fun hasValidLease(
            token: String,
            now: Instant,
        ): Boolean =
            status == DispatchStatus.CLAIMED &&
                leaseToken == token &&
                leaseExpiresAt?.isAfter(now) == true

        fun released(): StoredDispatch = copy(status = DispatchStatus.PENDING, leaseToken = null, leaseExpiresAt = null)

        fun toQueued(notifiedWaiters: Int): DispatchQueued =
            DispatchQueued(
                id = id,
                kind = kind,
                vendor = vendor,
                payloadVersion = payloadVersion,
                expiresAt = pendingExpiresAt,
                notifiedWaiters = notifiedWaiters,
            )

        fun toClaimed(
            token: String,
            leaseExpiresAt: Instant,
        ): DispatchClaimed =
            DispatchClaimed(
                id = id,
                kind = kind,
                vendor = vendor,
                payloadVersion = payloadVersion,
                payload = payload,
                leaseToken = token,
                leaseExpiresAt = leaseExpiresAt,
                expiresAt = pendingExpiresAt,
            )
    }
}
