package ca.floo.roadtrip.service.notification

import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/**
 * Business seam for delivering watch notifications. Availability code depends
 * on this aggregate interface instead of Slack, Resend, or their wire types.
 * A watch alert supplies one or more [NotificationTarget]s; this service owns
 * routing each target to its transport and formatting.
 */
interface NotificationService {
    /**
     * Renders and sends a watch's lifecycle/status card (watching, paused,
     * done, stopped) for [notice] to [targets]. The caller supplies plain
     * domain data; the service owns each target's message body. Never throws:
     * a delivery failure is surfaced as `false` so a notification problem
     * cannot break the caller's flow.
     */
    suspend fun sendWatchStatus(
        notice: WatchStatusNotice,
        targets: List<NotificationTarget>,
    ): Boolean

    /**
     * Renders and sends the "Sites available" alert for the openings in a
     * watch's `[startDate, endDate)` window. The caller supplies
     * fully-hydrated [WatchOpening]s (label, campground, booking URL) and the
     * [watchId] used as the interactive-button payload; the service owns the
     * mapping to the Slack message body. Never throws; returns `false` on a
     * delivery failure or when Slack is disabled.
     */
    suspend fun sendWatchOpenings(
        watchId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
        openings: List<WatchOpening>,
        targets: List<NotificationTarget>,
        appRootUrl: String? = null,
    ): Boolean

    /**
     * Reports a backend-owned one-shot ATC result to [targets].
     */
    suspend fun sendAtcResult(
        watchId: Long,
        vendor: String,
        status: String,
        request: JsonObject,
        response: JsonObject?,
        targets: List<NotificationTarget>,
    ): Boolean = false

    /**
     * Updates a Slack card in place after a user pressed an interactive button
     * (pause / resume / delete): re-renders [notice] and posts it to Slack's
     * `response_url` from the interaction payload. Never throws.
     */
    suspend fun postResponseWatchStatus(
        responseUrl: String,
        notice: WatchStatusNotice,
    ): Boolean

    /**
     * Replaces a clicked Slack card whose watch id no longer resolves. This is
     * the stale-card path for deleted/reset watches: no mutation happened, but
     * the user still gets immediate feedback and the dead buttons disappear.
     */
    suspend fun postResponseStaleWatch(
        responseUrl: String,
        watchId: Long,
    ): Boolean
}
