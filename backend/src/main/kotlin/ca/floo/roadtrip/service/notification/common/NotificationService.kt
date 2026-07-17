package ca.floo.roadtrip.service.notification.common

import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/**
 * One notification transport behind a target-aware port. Slack, email, and any
 * future SMS service implement this interface; [canHandle] keeps dispatch out
 * of transport implementations and avoids a central `when` over target types.
 */
interface NotificationService {
    fun canHandle(target: NotificationTarget): Boolean

    /**
     * Renders and sends a watch's lifecycle/status card (watching, paused,
     * done, stopped) for [notice] to [target]. Transports that do not support
     * this message type return `false`.
     */
    suspend fun sendWatchStatus(
        notice: WatchStatusNotice,
        target: NotificationTarget,
    ): Boolean = false

    /**
     * Renders and sends the "Sites available" alert for the openings in a
     * watch's `[startDate, endDate)` window. The caller supplies
     * fully-hydrated [WatchOpening]s (label, campground, booking URL) and the
     * [watchId] used by interactive targets.
     */
    suspend fun sendWatchOpenings(
        watchId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
        openings: List<WatchOpening>,
        target: NotificationTarget,
        appRootUrl: String? = null,
    ): Boolean = false

    /**
     * Reports a backend-owned one-shot ATC result.
     */
    suspend fun sendAtcResult(
        watchId: Long,
        vendor: String,
        status: String,
        request: JsonObject,
        response: JsonObject?,
        target: NotificationTarget,
    ): Boolean = false
}
