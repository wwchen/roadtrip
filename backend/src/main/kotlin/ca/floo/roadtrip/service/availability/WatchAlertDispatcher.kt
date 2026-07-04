package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.clients.slack.SlackNotifier
import ca.floo.roadtrip.models.availability.CellTransition
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.repo.AvailabilityHeatmapRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.time.LocalDate

/** The only trigger kind that dispatches today. Others (e.g. `atc`) are stored
 *  but inert — they match no branch here. */
const val SLACK_NOTIFY_KIND = "slack_notify"

private const val MAX_SITES_IN_MESSAGE = 10

/** Grafana dashboards the alert deep-links to. The watch drill-down takes the
 *  firing watch's id (`var-watch_id`); the cube matrix takes a POI
 *  (`var-poi_id`) for the current availability grid. */
private const val WATCH_DASHBOARD_UID = "reservable-watch-drill"
private const val CELL_MATRIX_UID = "availability-cell-matrix"

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
    private val heatmaps: AvailabilityHeatmapRepo,
    private val grafanaRootUrl: String?,
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

            val channel = resolveChannel(watch)
            if (channel == null) {
                log.warn("watch {} matched but no Slack channel (no override, no default); skipping", watch.id)
                continue
            }
            postOpenings(watch, channel, covered, reservablesById)
        }
    }

    /**
     * The "first message" for a freshly created/updated watch. Unlike [dispatch]
     * — which reacts to cube *edges* — this reads the current cube face for the
     * watch's window and always sends exactly one message, so a watch created on
     * an already-open site is not silently stranded (its openings pre-date any
     * future edge). Gated to active `slack_notify` watches; fire-and-forget from
     * the route, so like [dispatch] it never throws into its caller.
     *
     * Three states, mirroring what the cube can tell us:
     *  - **some cells bookable** → the same openings alert [dispatch] sends; a
     *    real trigger, so `stopWhenTriggered` still marks the watch `DONE`.
     *  - **cells known, none bookable** → informational "nothing open yet".
     *  - **no cells (cold POI)** → informational "not checked yet"; the immediate
     *    poll `create()` schedules will observe the window and its first
     *    observation is itself an edge, so the real opening fires via [dispatch].
     * The two informational states never mark a watch `DONE`.
     */
    suspend fun dispatchInitial(watch: AvailabilityWatchRepo.Watch) {
        if (notifier == null) return
        if (SLACK_NOTIFY_KIND !in watch.triggerKinds) return
        if (watch.status != WatchStatus.ACTIVE) return
        val channel = resolveChannel(watch)
        if (channel == null) {
            log.warn("watch {} created/updated but no Slack channel (no override, no default); skipping", watch.id)
            return
        }
        val reservables = scopeResolver.resolve(watch)
        val reservablesById = reservables.associateBy { it.id }
        val cells = heatmaps.loadHeatmap(reservables.map { it.id }, datesInWindow(watch))
        val bookable = cells.filter { it.available }
        if (bookable.isNotEmpty()) {
            val covered = bookable.map { CellTransition(it.reservableId, it.targetDate, it.status) }
            postOpenings(watch, channel, covered, reservablesById)
        } else {
            notifier.notify(channel, buildInitialStatusMessage(watch, reservables, cubeKnown = cells.isNotEmpty()))
        }
    }

    private fun resolveChannel(watch: AvailabilityWatchRepo.Watch): String? = watch.channelOverride() ?: defaultChannel

    /** Posts the openings alert for one watch and, on a successful post, marks a
     *  `stopWhenTriggered` watch `DONE` — so a delivery failure never silences a
     *  watch we could not notify on. Shared by the edge and initial paths. */
    private suspend fun postOpenings(
        watch: AvailabilityWatchRepo.Watch,
        channel: String,
        covered: List<CellTransition>,
        reservablesById: Map<Long, Reservable>,
    ) {
        val fired = notifier!!.notify(channel, buildMessage(covered, reservablesById, watch.id))
        if (fired && watch.stopWhenTriggered) {
            watches.update(watch.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.DONE))
        }
    }

    private fun buildMessage(
        covered: List<CellTransition>,
        reservablesById: Map<Long, Reservable>,
        watchId: Long,
    ): String {
        val ordered = covered.sortedWith(compareBy({ it.reservableId }, { it.targetDate }))
        // Distinct POIs the openings sit under, for the cell-matrix (cube) link.
        // Collected in the same pass that resolves each reservable's provider for
        // its booking link, so we resolve each target once.
        val poiIds = LinkedHashSet<Long>()
        val lines =
            ordered.take(MAX_SITES_IN_MESSAGE).joinToString("\n") { t ->
                // covered was filtered to reservables in this map, so the key exists.
                val r = reservablesById.getValue(t.reservableId)
                val target = targets.resolve(r)
                target?.parentPoiId?.let(poiIds::add)
                val label = r.name ?: r.rid.encode()
                val loop = r.loop?.let { " ($it)" }.orEmpty()
                // Booking link, if the reservable's provider exposes one — the
                // URL scheme is the adapter's, never this dispatcher's.
                val url = target?.provider?.bookingUrl(r.rid, t.targetDate)
                if (url != null) "• *$label*$loop — ${t.targetDate} <$url|book>" else "• *$label*$loop — ${t.targetDate}"
            }
        val count = ordered.size
        val header = "⛺ $count campsite opening${if (count == 1) "" else "s"} available"
        val more = if (count > MAX_SITES_IN_MESSAGE) "\n…and ${count - MAX_SITES_IN_MESSAGE} more" else ""
        return "$header\n$lines$more${dashboardLinks(watchId, poiIds)}"
    }

    /** Grafana deep links appended to an alert when the Grafana host is
     *  configured: the watch drill-down and the availability-cube matrix for each
     *  POI the openings sit under. Empty when [grafanaRootUrl] is null. */
    private fun dashboardLinks(
        watchId: Long,
        poiIds: Set<Long>,
    ): String {
        val grafanaRootUrl = grafanaRootUrl ?: return ""
        val watch = "\n📊 <$grafanaRootUrl/d/$WATCH_DASHBOARD_UID?var-watch_id=$watchId|watch dashboard>"
        val cube =
            when (poiIds.size) {
                0 -> ""
                1 -> "\n🗓 <$grafanaRootUrl/d/$CELL_MATRIX_UID?var-poi_id=${poiIds.first()}|availability grid>"
                else ->
                    "\n🗓 " +
                        poiIds.joinToString(" · ") { "<$grafanaRootUrl/d/$CELL_MATRIX_UID?var-poi_id=$it|grid $it>" }
            }
        return "$watch$cube"
    }

    /** Body of the initial message when nothing is bookable in the window:
     *  either the cube has no observation for it yet ([cubeKnown] = false,
     *  "unknown") or every cell is currently taken. Never a trigger — it carries
     *  the watch's scope, window, and dashboards so the user can confirm the
     *  watch is live, but lists no openings. POI links come from the watch's
     *  POI-scoped targets (reservable-scoped targets carry no POI). */
    private fun buildInitialStatusMessage(
        watch: AvailabilityWatchRepo.Watch,
        reservables: List<Reservable>,
        cubeKnown: Boolean,
    ): String {
        val scope = scopeLabel(reservables)
        val window = "${watch.startDate} → ${watch.endDate}"
        val state = if (cubeKnown) "nothing available right now" else "availability not checked yet"
        val poiIds = watch.targets.mapNotNull { it.poiId }.toSet()
        return "👀 Watching $scope for $window — $state. I'll alert the moment a site opens.${dashboardLinks(watch.id, poiIds)}"
    }

    /** Short human label for a watch's scope: the single site's name when the
     *  watch covers exactly one reservable, else the reservable count. */
    private fun scopeLabel(reservables: List<Reservable>): String =
        when (reservables.size) {
            1 -> {
                val r = reservables.first()
                "*${r.name ?: r.rid.encode()}*${r.loop?.let { " ($it)" }.orEmpty()}"
            }
            else -> "${reservables.size} sites"
        }

    /** The half-open [startDate, endDate) day list — the same window contract
     *  [withinWindow] enforces on the edge path — for reading the current cube. */
    private fun datesInWindow(watch: AvailabilityWatchRepo.Watch): List<LocalDate> =
        generateSequence(watch.startDate) { d -> d.plusDays(1).takeIf { it.isBefore(watch.endDate) } }.toList()
}

// The watch window is half-open [startDate, endDate) — the same contract the
// provider fetch and the heatmap use. endDate is the checkout day, not a
// watched night, so it is excluded: with coalesced pollers a longer watch can
// pull a transition on a shorter watch's endDate into the shared fetch, and an
// inclusive bound would misfire the shorter watch (and wrongly mark it done).
private fun LocalDate.withinWindow(watch: AvailabilityWatchRepo.Watch): Boolean = !isBefore(watch.startDate) && isBefore(watch.endDate)

private fun AvailabilityWatchRepo.Watch.channelOverride(): String? =
    (triggerConfig["channel"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.isNotBlank() }
