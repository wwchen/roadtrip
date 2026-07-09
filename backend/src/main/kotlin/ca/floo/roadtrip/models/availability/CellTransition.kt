package ca.floo.roadtrip.models.availability

import java.time.LocalDate

/**
 * One availability-cube edge observed in a poll tick: the cell
 * `(campsiteId, targetDate)` changed to [status]. Produced by the poller when
 * it writes the cube (a transition = a status change from the prior stored
 * value) and consumed by [ca.floo.roadtrip.service.availability.WatchAlertDispatcher],
 * which alerts on the subset whose [status] `isOnlineBookable`.
 */
data class CellTransition(
    val campsiteId: Long,
    val targetDate: LocalDate,
    val status: AvailabilityStatus,
)
