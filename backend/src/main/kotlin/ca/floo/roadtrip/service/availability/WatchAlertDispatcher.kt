package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.CellTransition
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.repo.AvailabilityHeatmapRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.service.notification.SlackNotificationService
import ca.floo.roadtrip.service.notification.WatchOpening
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/** The only trigger kind that dispatches today. Others (e.g. `atc`) are stored
 *  but inert — they match no branch here. */
const val SLACK_NOTIFY_KIND = "slack_notify"

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
 * This class only decides *which* watches fire and hydrates their openings; the
 * [SlackNotificationService] owns the message rendering and delivery (and no-ops
 * when Slack is unconfigured). Nothing here throws into the caller: the service
 * swallows its own failures and the executor wraps this call best-effort. A
 * watch's channel override (if any) is passed through; the service falls back to
 * its default channel.
 */
internal class WatchAlertDispatcher(
    private val slack: SlackNotificationService,
    private val scopeResolver: WatchScopeResolver,
    private val watches: AvailabilityWatchRepo,
    private val targets: AvailabilityTargetResolver,
    private val pois: PoiServingRepo,
    private val heatmaps: AvailabilityHeatmapRepo,
    private val grafanaRootUrl: String?,
) {
    suspend fun dispatch(
        liveWatches: List<AvailabilityWatchRepo.Watch>,
        transitions: List<CellTransition>,
    ) {
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
            postOpenings(watch, covered, reservablesById)
        }
    }

    /**
     * The "status message" for a `slack_notify` watch whose lifecycle just
     * changed — created, updated, or paused. Unlike [dispatch], which reacts to
     * cube *edges*, this always sends exactly one message reflecting the watch's
     * current status, so a watch created on an already-open site is not silently
     * stranded (its openings pre-date any future edge). Fire-and-forget from the
     * route, so like [dispatch] it never throws into its caller.
     *
     * A **paused/done** watch reports its lifecycle state and stops — no
     * availability lookup, never a trigger. An **active** watch reads the current
     * cube face for its window:
     *  - **some cells bookable** → the same openings alert [dispatch] sends; a
     *    real trigger, so `stopWhenTriggered` still marks the watch `DONE`.
     *  - **cells known, none bookable** → informational "nothing open yet".
     *  - **no cells (cold POI)** → informational "not checked yet"; the immediate
     *    poll `create()` schedules will observe the window and its first
     *    observation is itself an edge, so the real opening fires via [dispatch].
     * Only the bookable state ever marks a watch `DONE`.
     */
    suspend fun dispatchInitial(watch: AvailabilityWatchRepo.Watch) {
        if (SLACK_NOTIFY_KIND !in watch.triggerKinds) return

        val reservables = scopeResolver.resolve(watch)
        if (watch.status != WatchStatus.ACTIVE) {
            slack.sendMessage(buildLifecycleMessage(watch, reservables), watch.channelOverride())
            return
        }
        val reservablesById = reservables.associateBy { it.id }
        val cells = heatmaps.loadHeatmap(reservables.map { it.id }, datesInWindow(watch))
        val bookable = cells.filter { it.available }
        if (bookable.isNotEmpty()) {
            val covered = bookable.map { CellTransition(it.reservableId, it.targetDate, it.status) }
            postOpenings(watch, covered, reservablesById)
        } else {
            slack.sendMessage(buildWatchingMessage(watch, reservables, cubeKnown = cells.isNotEmpty()), watch.channelOverride())
        }
    }

    /** Hydrates the covered cells into [WatchOpening]s, hands them to the
     *  notification service (which owns the message rendering), and — on a
     *  successful post — marks a `stopWhenTriggered` watch `DONE`, so a delivery
     *  failure never silences a watch we could not notify on. Shared by the edge
     *  and initial paths. */
    private suspend fun postOpenings(
        watch: AvailabilityWatchRepo.Watch,
        covered: List<CellTransition>,
        reservablesById: Map<Long, Reservable>,
    ) {
        val openings = hydrateOpenings(covered, reservablesById)
        val fired = slack.sendWatchOpenings(watch.startDate, watch.endDate, openings, watch.channelOverride())
        if (fired && watch.stopWhenTriggered) {
            watches.update(watch.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.DONE))
        }
    }

    /** Resolves each covered cell to a [WatchOpening] — the reservable's display
     *  label/loop/type, its parent campground (id + name, each POI fetched once),
     *  and the provider booking URL — so the notification layer only formats. */
    private fun hydrateOpenings(
        covered: List<CellTransition>,
        reservablesById: Map<Long, Reservable>,
    ): List<WatchOpening> {
        val poiNames = HashMap<Long, String?>()
        return covered.map { t ->
            // covered was filtered to reservables in this map, so the key exists.
            val r = reservablesById.getValue(t.reservableId)
            val target = targets.resolve(r)
            WatchOpening(
                label = r.name ?: r.rid.encode(),
                loop = r.loop,
                siteType = r.siteType,
                date = t.targetDate,
                campgroundId = target?.parentPoiId,
                campground = target?.parentPoiId?.let { poiNames.getOrPut(it) { pois.fetchPoiById(it)?.name } },
                // Booking link, if the reservable's provider exposes one — the URL
                // scheme is the adapter's, never this dispatcher's.
                bookingUrl = target?.provider?.bookingUrl(r.rid, t.targetDate),
            )
        }
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

    /** Body of an active watch's message when nothing is bookable in the window:
     *  either the cube has no observation for it yet ([cubeKnown] = false,
     *  "unknown") or every cell is currently taken. Never a trigger — it carries
     *  the watch's scope, window, and dashboards so the user can confirm the
     *  watch is live, but lists no openings. POI links come from the watch's
     *  POI-scoped targets (reservable-scoped targets carry no POI). */
    private fun buildWatchingMessage(
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

    /** Message for a non-active watch (paused or done). Reports the lifecycle
     *  state only — no availability lookup, never a trigger — so pausing a watch
     *  posts a clear "stopped watching" note rather than going silent. */
    private fun buildLifecycleMessage(
        watch: AvailabilityWatchRepo.Watch,
        reservables: List<Reservable>,
    ): String {
        val scope = scopeLabel(reservables)
        val window = "${watch.startDate} → ${watch.endDate}"
        val poiIds = watch.targets.mapNotNull { it.poiId }.toSet()
        val body =
            when (watch.status) {
                WatchStatus.PAUSED -> "⏸ Paused watching $scope for $window — I won't alert until it's resumed."
                WatchStatus.DONE -> "✅ Done watching $scope for $window."
                WatchStatus.ACTIVE -> "👀 Watching $scope for $window." // unreachable; active takes the snapshot path
            }
        return "$body${dashboardLinks(watch.id, poiIds)}"
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
