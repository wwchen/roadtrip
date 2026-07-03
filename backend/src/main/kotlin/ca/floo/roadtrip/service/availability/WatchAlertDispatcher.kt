package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.clients.slack.SlackNotifier
import ca.floo.roadtrip.models.availability.CellTransition
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.time.LocalDate

/** The only trigger kind that dispatches today. Others (e.g. `atc`) are stored
 *  but inert — they match no branch here. */
const val SLACK_NOTIFY_KIND = "slack_notify"

private const val MAX_SITES_IN_MESSAGE = 10

/** Grafana dashboard the alert deep-links to (per-watch drill-down). Its
 *  `watch_id` template var takes the firing watch's id. */
private const val WATCH_DASHBOARD_UID = "reservable-watch-drill"

/**
 * Turns cube edges into Slack alerts. Called once per poller run, after the
 * cube write, with the transitions that tick produced and the poller's live
 * watches (both already in the executor's hand).
 *
 * For each live watch it keeps the transitions that fall inside the watch's
 * reservable set and date window, and — if the watch opted into
 * [SLACK_NOTIFY_KIND] — posts one message. A watch with `stopWhenTriggered`
 * goes `DONE` **only after a post actually succeeds**, so a delivery failure
 * never silences a watch we could not notify on.
 *
 * [notifier] is null when Slack is unconfigured; then the whole path no-ops.
 * Nothing here throws into the caller: the notifier swallows its own failures
 * and the executor wraps this call best-effort.
 */
internal class WatchAlertDispatcher(
    private val notifier: SlackNotifier?,
    private val scopeResolver: WatchScopeResolver,
    private val watches: AvailabilityWatchRepo,
    private val targets: AvailabilityTargetResolver,
    private val grafanaRootUrl: String,
    private val defaultChannel: String?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun dispatch(
        liveWatches: List<AvailabilityWatchRepo.Watch>,
        transitions: List<CellTransition>,
    ) {
        if (notifier == null) return
        val bookable = transitions.filter { it.status.isOnlineBookable }
        if (bookable.isEmpty()) return

        for (watch in liveWatches) {
            if (SLACK_NOTIFY_KIND !in watch.triggerKinds) continue
            val reservablesById = scopeResolver.resolve(watch).associateBy { it.id }
            val covered =
                bookable.filter { t ->
                    t.reservableId in reservablesById && t.targetDate.withinWindow(watch)
                }
            if (covered.isEmpty()) continue

            val channel = watch.channelOverride() ?: defaultChannel
            if (channel == null) {
                log.warn("watch {} matched but no Slack channel (no override, no default); skipping", watch.id)
                continue
            }
            val fired = notifier.notify(channel, buildMessage(covered, reservablesById, watch.id))
            if (fired && watch.stopWhenTriggered) {
                watches.update(watch.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.DONE))
            }
        }
    }

    private fun buildMessage(
        covered: List<CellTransition>,
        reservablesById: Map<Long, Reservable>,
        watchId: Long,
    ): String {
        val ordered = covered.sortedWith(compareBy({ it.reservableId }, { it.targetDate }))
        val lines =
            ordered.take(MAX_SITES_IN_MESSAGE).joinToString("\n") { t ->
                val r = reservablesById[t.reservableId]
                val label = r?.name ?: r?.rid?.encode() ?: "site ${t.reservableId}"
                val loop = r?.loop?.let { " ($it)" }.orEmpty()
                // Booking link, if the reservable's provider exposes one — the
                // URL scheme is the adapter's, never this dispatcher's.
                val url = r?.let { targets.resolve(it)?.provider?.bookingUrl(it.rid, t.targetDate) }
                if (url != null) "• *$label*$loop — ${t.targetDate} <$url|book>" else "• *$label*$loop — ${t.targetDate}"
            }
        val count = ordered.size
        val header = "⛺ $count campsite opening${if (count == 1) "" else "s"} available"
        val more = if (count > MAX_SITES_IN_MESSAGE) "\n…and ${count - MAX_SITES_IN_MESSAGE} more" else ""
        val dashboard = "\n📊 <$grafanaRootUrl/d/$WATCH_DASHBOARD_UID?var-watch_id=$watchId|watch dashboard>"
        return "$header\n$lines$more$dashboard"
    }
}

private fun LocalDate.withinWindow(watch: AvailabilityWatchRepo.Watch): Boolean = !isBefore(watch.startDate) && !isAfter(watch.endDate)

private fun AvailabilityWatchRepo.Watch.channelOverride(): String? =
    (triggerConfig["channel"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.isNotBlank() }
