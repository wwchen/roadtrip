package ca.floo.roadtrip.service.notification.slack

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.availability.WatchStatus
import ca.floo.roadtrip.service.notification.common.WatchStatusNotice
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val blockActionsJson = Json { ignoreUnknownKeys = true }

/**
 * Server-side dispatch for a Slack interactivity `block_actions` payload — the
 * inbound half of the outbound watch cards. The Ktor route
 * ([ca.floo.roadtrip.routes.api.slack.slackInteractivityRoute]) verifies the signature and
 * hands the parsed payload here; this handler applies the mutation (pause /
 * resume / delete) and re-renders the card in place through the
 * [SlackResponseSender.postResponseWatchStatus] one-shot URL.
 *
 * URL-button action ids (Reserve, Grid, Map, Dashboard) route to a silent
 * no-op: Slack still fires an interaction payload for them, but the redirect
 * already happened client-side and there's no state change to acknowledge.
 * Unknown action ids are logged and ignored so a forgotten button in an old
 * card can't crash the endpoint.
 *
 * Depends on a narrow [Watches] port (rather than the wider
 * `AvailabilityWatchService` / `AvailabilityWatchRepo` / `WatchAlertDispatcher`)
 * so the two watch operations we need to hit here can be stubbed in tests
 * without spinning up a DB, and so a future refactor of any of those wider
 * services doesn't force the handler to change shape.
 */
internal class SlackInteractivityHandler(
    private val watches: Watches,
    private val slack: SlackResponseSender,
) {
    /** The two mutations + one snapshot the interactivity handler needs from
     *  the watch layer, wrapped in a port the composition root implements by
     *  delegating to `AvailabilityWatchService` + `AvailabilityWatchRepo` +
     *  `WatchAlertDispatcher`. Kept small on purpose — this is the seam a
     *  test needs to fake, not the whole watch surface. */
    interface Watches {
        /** Applies [status] to watch [id] and returns the updated row; null when
         *  no row with that id exists (a stale card from before delete). */
        fun setStatus(
            id: Long,
            status: WatchStatus,
        ): AvailabilityWatchRepo.Watch?

        /** Snapshots the watch (so the goodbye card can still resolve its scope)
         *  then deletes it. Returns the pre-delete snapshot; null when the row
         *  was already gone (double-click, race with the HTTP DELETE route). */
        fun snapshotAndDelete(id: Long): AvailabilityWatchRepo.Watch?

        /** Builds a [WatchStatusNotice] for the given watch + [state] — same
         *  logic the outbound dispatcher uses, so the re-rendered card looks
         *  identical to the one that would arrive on a status change from any
         *  other code path. */
        fun buildStatusNotice(
            watch: AvailabilityWatchRepo.Watch,
            state: WatchStatusNotice.State,
        ): WatchStatusNotice
    }

    private val log = LoggerFactory.getLogger(javaClass)

    /** Applies whatever mutation [payload]'s first action names, then updates
     *  the Slack card in place. Best-effort: any exception from the repo or
     *  Slack layer is logged and swallowed — the caller (a Ktor coroutine
     *  launched off the route thread) already ack'd 200 to Slack and can't
     *  surface a delayed error to the user. */
    suspend fun handle(payload: BlockActionsPayload) {
        val action = payload.actions.firstOrNull()
        if (action == null) {
            log.info("Slack interactivity handler: payload had no actions, dropping")
            return
        }
        val responseUrl = payload.responseUrl
        if (responseUrl == null) {
            log.info("Slack interactivity handler: payload had no response_url, dropping (action={})", action.actionId)
            return
        }
        when (action.actionId) {
            SlackWatchCard.ACTION_WATCH_PAUSE ->
                mutateWatchStatus(action, responseUrl, WatchStatus.PAUSED, WatchStatusNotice.State.PAUSED)
            SlackWatchCard.ACTION_WATCH_RESUME ->
                mutateWatchStatus(action, responseUrl, WatchStatus.ACTIVE, WatchStatusNotice.State.WATCHING)
            SlackWatchCard.ACTION_WATCH_DELETE ->
                deleteWatch(action, responseUrl)
            // URL button follow-ups — Slack fires them alongside the redirect;
            // there's no server-side work to do, but returning silently keeps
            // the endpoint from logging spurious "unknown action" warnings.
            SlackWatchCard.ACTION_RESERVE_SITE,
            SlackWatchCard.ACTION_OPEN_GRID,
            SlackWatchCard.ACTION_OPEN_MAP,
            SlackWatchCard.ACTION_OPEN_DASHBOARD,
            -> log.info("Slack interactivity handler: URL button {} — silent ack (redirect happened client-side)", action.actionId)
            else -> log.warn("Slack interactivity handler: unknown action_id={}, dropping", action.actionId)
        }
    }

    private suspend fun mutateWatchStatus(
        action: BlockAction,
        responseUrl: String,
        newStatus: WatchStatus,
        newNoticeState: WatchStatusNotice.State,
    ) {
        val watchId = action.value?.toLongOrNull()
        if (watchId == null) {
            log.warn("Slack interactivity {} without a numeric watch id in value (raw={})", action.actionId, action.value)
            return
        }
        log.info("Slack interactivity {} → applying status={} to watch {}", action.actionId, newStatus, watchId)
        val updated = watches.setStatus(watchId, newStatus)
        if (updated == null) {
            log.warn("Slack interactivity {} for missing watch {} (stale card?)", action.actionId, watchId)
            val ok = slack.postResponseStaleWatch(responseUrl, watchId)
            log.info("Slack interactivity {} missing watch={} stale response_url update ok={}", action.actionId, watchId, ok)
            return
        }
        val ok = slack.postResponseWatchStatus(responseUrl, watches.buildStatusNotice(updated, newNoticeState))
        log.info("Slack interactivity {} watch={} response_url update ok={}", action.actionId, watchId, ok)
    }

    private suspend fun deleteWatch(
        action: BlockAction,
        responseUrl: String,
    ) {
        val watchId = action.value?.toLongOrNull()
        if (watchId == null) {
            log.warn("Slack interactivity delete without a numeric watch id in value (raw={})", action.value)
            return
        }
        log.info("Slack interactivity delete → snapshotting + deleting watch {}", watchId)
        val snapshot = watches.snapshotAndDelete(watchId)
        if (snapshot == null) {
            log.warn("Slack interactivity delete for missing watch {} (stale card or already deleted?)", watchId)
            val ok = slack.postResponseStaleWatch(responseUrl, watchId)
            log.info("Slack interactivity delete missing watch={} stale response_url update ok={}", watchId, ok)
            return
        }
        val ok = slack.postResponseWatchStatus(responseUrl, watches.buildStatusNotice(snapshot, WatchStatusNotice.State.STOPPED))
        log.info("Slack interactivity delete watch={} response_url update ok={}", watchId, ok)
    }

    companion object {
        /**
         * Extracts the interesting bits of a Slack `block_actions` payload —
         * the first action's id + value, plus the response_url. Payload
         * shapes vary by interaction type; this parser only handles the
         * button-in-a-card shape our cards use.
         */
        fun parse(json: String): BlockActionsPayload? =
            runCatching { blockActionsJson.decodeFromString(BlockActionsPayload.serializer(), json) }
                .getOrNull()
    }
}
