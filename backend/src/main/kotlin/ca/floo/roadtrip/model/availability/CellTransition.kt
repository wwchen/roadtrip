package ca.floo.roadtrip.model.availability

import java.time.Instant
import java.time.LocalDate

data class KnownStatus(
    val status: AvailabilityStatus,
    val observedAt: Instant,
)

/**
 * One availability-cube edge observed in a poll tick: the cell
 * `(campsiteId, targetDate)` changed to [status]. Produced by the poller when
 * it writes the cube (a transition = a status change from the prior stored
 * value) and consumed by [ca.floo.roadtrip.service.availability.WatchAlertDispatcher],
 * which alerts each watch on the subset that [opensSiteFor] it.
 */
data class CellTransition(
    val campsiteId: Long,
    val targetDate: LocalDate,
    val status: AvailabilityStatus,
    val lastKnown: KnownStatus?,
)

fun CellTransition.opensSiteFor(watchSince: Instant): Boolean =
    status.isOnlineBookable &&
        (lastKnown == null || !lastKnown.status.isOnlineBookable || watchSince.isAfter(lastKnown.observedAt))
