package ca.floo.roadtrip.service.notification

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
     * Sends plain [text] to [channel], or to the service's configured default
     * channel when [channel] is null. Never throws: a delivery failure — or
     * having no channel to send to — is surfaced as `false` so a notification
     * problem can't break the caller's flow.
     */
    suspend fun sendMessage(
        text: String,
        channel: String? = null,
    ): Boolean

    /**
     * Renders and sends the rich "Campsites Available!" alert for the openings in
     * a watch's `[startDate, endDate)` window. The caller supplies fully-hydrated
     * [WatchOpening]s (label, campground, booking URL); the service owns the
     * mapping to the Slack message body. Never throws; returns `false` on a
     * delivery failure or when Slack is disabled.
     */
    suspend fun sendWatchOpenings(
        startDate: LocalDate,
        endDate: LocalDate,
        openings: List<WatchOpening>,
        channel: String? = null,
    ): Boolean
}
