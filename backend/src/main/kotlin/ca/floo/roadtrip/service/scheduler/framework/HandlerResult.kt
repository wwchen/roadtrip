package ca.floo.roadtrip.service.scheduler.framework

import java.time.OffsetDateTime

/**
 * Result of one handler invocation. The handler returns the next
 * scheduling timestamp; the scheduler writes it back via
 * [SchedulableRepo.release] together with the run timestamp.
 *
 * Handlers should catch their own domain errors and return a usable
 * [HandlerResult] anyway; uncaught throwables are logged and the row's
 * lease is released with the original cadence so we don't lose the row.
 */
data class HandlerResult(
    val nextRunAt: OffsetDateTime,
)
