package ca.floo.roadtrip.service.scheduler

import java.time.Duration
import java.time.OffsetDateTime

/**
 * Contract a scheduled-work table must satisfy. Each row is a unit of
 * work the scheduler can claim, hand to a handler, and re-schedule.
 *
 * Implementations live in `repo/` (for example, [AvailabilityJobRepo]
 * fronts `availability_job`).
 */
interface SchedulableRepo<T> {
    /**
     * Claim up to [limit] eligible rows by setting a fresh claim token
     * and a lease. Rows with expired leases are eligible for re-claim.
     * Implementations use `FOR UPDATE SKIP LOCKED` so concurrent ticks
     * never hand the same row to two callers.
     */
    fun claimDue(
        now: OffsetDateTime,
        limit: Int,
        leaseDuration: Duration,
    ): List<T>

    /**
     * Release a claim after the handler runs to completion (success or
     * caught failure). [nextRunAt] is the new schedule; the handler
     * computes it from the row's cadence + the run timestamp. Returns
     * false when the claim_token doesn't match, signalling the lease
     * was reclaimed by [reclaimExpired] mid-run.
     */
    fun release(
        id: Long,
        token: String,
        nextRunAt: OffsetDateTime,
        ranAt: OffsetDateTime,
    ): Boolean

    /**
     * Boot-time / periodic recovery: clear claim_token + claimed_until
     * on rows whose lease has expired without a release. Returns the
     * number of rows reset.
     */
    fun reclaimExpired(now: OffsetDateTime): Int
}

/**
 * Things a scheduled row carries that the scheduler reads. Intentionally
 * minimal — anything domain-specific stays inside the row type and is
 * read by the handler.
 */
interface Schedulable {
    val id: Long
    val claimToken: String?
}
