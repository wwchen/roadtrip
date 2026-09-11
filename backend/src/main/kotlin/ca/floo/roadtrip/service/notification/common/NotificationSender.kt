package ca.floo.roadtrip.service.notification.common

import java.time.LocalDate

/**
 * Aggregate notification seam used by availability code. It accepts a list of
 * concrete targets and delegates each one to the first [NotificationService]
 * that can handle it.
 */
interface NotificationSender {
    suspend fun sendWatchStatus(
        notice: WatchStatusNotice,
        targets: List<NotificationTarget>,
    ): Boolean

    suspend fun sendWatchOpenings(
        watchId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
        openings: List<WatchOpening>,
        targets: List<NotificationTarget>,
        appRootUrl: String? = null,
    ): Boolean

    /** See [AtcResultNotice] for what one outcome carries and why. */
    suspend fun sendAtcResult(
        notice: AtcResultNotice,
        targets: List<NotificationTarget>,
    ): Boolean = false
}
