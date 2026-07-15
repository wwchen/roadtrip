package ca.floo.roadtrip.service.availability

import java.time.Duration
import java.time.Instant

internal interface DispatchStore {
    fun enqueue(
        input: DispatchCreateInput,
        pendingTtl: Duration,
        now: Instant,
    ): DispatchQueued

    fun claim(
        selector: DispatchClaimSelector,
        leaseDuration: Duration,
        now: Instant,
    ): DispatchClaimed?

    fun heartbeat(
        id: Long,
        leaseToken: String,
        leaseDuration: Duration,
        now: Instant,
    ): DispatchLeaseResult

    fun complete(
        id: Long,
        leaseToken: String,
        now: Instant,
    ): DispatchCompleteResult

    fun fail(
        id: Long,
        leaseToken: String,
        now: Instant,
    ): DispatchFailResult
}
