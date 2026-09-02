package ca.floo.roadtrip.service.notification.common

import kotlinx.serialization.json.JsonObject
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

    /** See [NotificationService.sendAtcResult] for why [error]/[detail] are
     *  first-class arguments rather than fields of [response]. */
    suspend fun sendAtcResult(
        watchId: Long,
        vendor: String,
        status: String,
        request: JsonObject,
        response: JsonObject?,
        error: String? = null,
        detail: String? = null,
        targets: List<NotificationTarget>,
    ): Boolean = false
}
