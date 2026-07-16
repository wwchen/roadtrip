package ca.floo.roadtrip.service.notification

import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/**
 * Business seam for delivering a Slack notification. Callers depend on this
 * interface — not on the [ca.floo.roadtrip.clients.slack.SlackClient] transport,
 * and not on its Block Kit wire types — so notification policy (channel routing,
 * formatting, future retries or alternate transports) can evolve without
 * touching every call site, and tests substitute a trivial fake instead of a
 * live workspace.
 */
interface SlackNotificationService {
    /**
     * Renders and sends a watch's lifecycle/status card (watching, paused,
     * done, stopped) for [notice] to [channel], or to the service's configured
     * default channel when [channel] is null. The caller supplies plain domain
     * data; the service owns the Block Kit body. Never throws: a delivery
     * failure — or having no channel to send to — is surfaced as `false` so a
     * notification problem can't break the caller's flow.
     */
    suspend fun sendWatchStatus(
        notice: WatchStatusNotice,
        channel: String? = null,
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
        channel: String? = null,
        appRootUrl: String? = null,
    ): Boolean

    /**
     * Reports a backend-owned one-shot ATC result.
     */
    suspend fun sendAtcResult(
        watchId: Long,
        vendor: String,
        status: String,
        request: JsonObject,
        response: JsonObject?,
        channel: String? = null,
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
