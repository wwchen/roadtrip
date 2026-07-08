package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.CellTransition
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.service.notification.SlackNotificationService
import ca.floo.roadtrip.service.notification.WatchOpening
import ca.floo.roadtrip.service.notification.WatchStatusNotice
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
 *
 * The notice-building helper [statusNoticeForWatch] is public so the Slack
 * interactivity handler can re-render a watch's card after a user pauses /
 * resumes / deletes it from the card itself, keeping the "which POI, which
 * dashboard URLs" logic in exactly one place.
 */
internal class WatchAlertDispatcher(
    private val slack: SlackNotificationService,
    private val scopeResolver: WatchScopeResolver,
    private val watches: AvailabilityWatchRepo,
    private val targets: AvailabilityTargetResolver,
    private val pois: PoiServingRepo,
    private val availability: AvailabilityRepo,
    private val grafanaRootUrl: String?,
    private val appRootUrl: String?,
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
            val state =
                when (watch.status) {
                    WatchStatus.PAUSED -> WatchStatusNotice.State.PAUSED
                    WatchStatus.DONE -> WatchStatusNotice.State.DONE
                    WatchStatus.ACTIVE -> WatchStatusNotice.State.WATCHING // unreachable; guarded above
                }
            slack.sendWatchStatus(statusNotice(watch, reservables, state), watch.channelOverride())
            return
        }
        val reservablesById = reservables.associateBy { it.id }
        val cells = availability.readCurrent(reservables.map { it.id }, datesInWindow(watch))
        val bookable = cells.filter { it.available }
        if (bookable.isNotEmpty()) {
            val covered = bookable.map { CellTransition(it.reservableId, it.targetDate, it.status) }
            postOpenings(watch, covered, reservablesById)
        } else {
            val state = if (cells.isNotEmpty()) WatchStatusNotice.State.WATCHING else WatchStatusNotice.State.UNCHECKED
            slack.sendWatchStatus(statusNotice(watch, reservables, state), watch.channelOverride())
        }
    }

    /**
     * The terminal "watch stopped" message for a `slack_notify` watch the user
     * just deleted. Sent once, from the delete route, *before* the row is
     * removed (its scope is still resolvable). Like [dispatchInitial] it never
     * throws into its caller and no-ops for a watch that never opted into Slack.
     */
    suspend fun dispatchStopped(watch: AvailabilityWatchRepo.Watch) {
        if (SLACK_NOTIFY_KIND !in watch.triggerKinds) return
        val reservables = scopeResolver.resolve(watch)
        slack.sendWatchStatus(statusNotice(watch, reservables, WatchStatusNotice.State.STOPPED), watch.channelOverride())
    }

    /**
     * Builds a status notice for [watch] in the given [state] without sending
     * it — used by the Slack interactivity handler to re-render a card after
     * the user pressed pause / resume / delete on it. Public because the
     * "which POI, which dashboard URLs" logic lives here and shouldn't be
     * duplicated in the interactivity path.
     */
    fun statusNoticeForWatch(
        watch: AvailabilityWatchRepo.Watch,
        state: WatchStatusNotice.State,
    ): WatchStatusNotice = statusNotice(watch, scopeResolver.resolve(watch), state)

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
        val fired =
            slack.sendWatchOpenings(
                watchId = watch.id,
                startDate = watch.startDate,
                endDate = watch.endDate,
                openings = openings,
                channel = watch.channelOverride(),
                appRootUrl = appRootUrl,
            )
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
                label = r.name ?: r.identity.encode(),
                loop = r.loop,
                siteType = r.siteType,
                date = t.targetDate,
                campgroundId = target?.parentPoiId,
                campground = target?.parentPoiId?.let { poiNames.getOrPut(it) { pois.fetchPoiById(it)?.name } },
                // Booking link, if the reservable's provider exposes one — the URL
                // scheme is the adapter's, never this dispatcher's. The parent
                // ref supplies vendor ids the per-site ref may omit (e.g. Aspira).
                bookingUrl = target?.let { it.provider.bookingUrl(r, it.parentRef, t.targetDate) },
            )
        }
    }

    /** Builds the plain-data [WatchStatusNotice] for a watch's lifecycle/status
     *  message. Carries the watch id (echoed as every interactive button's
     *  value), scope, window, and deep-link URLs (the Grafana watch dashboard,
     *  and per POI the web-app map page + Grafana grid); the notification
     *  layer owns the Block Kit rendering. A single-reservable watch reports
     *  its site name (+ loop); a broader one reports the count. POI links come
     *  from the watch's POI-scoped targets (reservable-scoped targets carry
     *  no POI). */
    private fun statusNotice(
        watch: AvailabilityWatchRepo.Watch,
        reservables: List<Reservable>,
        state: WatchStatusNotice.State,
    ): WatchStatusNotice {
        val poiIds = watch.targets.mapNotNull { it.poiId }.toSet()
        // A POI-scoped watch is "the campground" even when it expands to one
        // site; a reservable-scoped watch names the site. So the scope label
        // keys off the target kind, not the resolved reservable count.
        val siteScoped = poiIds.isEmpty()
        val single = reservables.singleOrNull().takeIf { siteScoped }
        return WatchStatusNotice(
            watchId = watch.id,
            state = state,
            siteCount = reservables.size,
            siteName = single?.let { it.name ?: it.identity.encode() },
            siteLoop = single?.loop,
            campgroundName = poiIds.singleOrNull()?.let { pois.fetchPoiById(it)?.name },
            startDate = watch.startDate,
            endDate = watch.endDate,
            dashboardUrl = grafanaRootUrl?.let { "$it/d/$WATCH_DASHBOARD_UID?var-watch_id=${watch.id}" },
            poiLinks = poiLinks(poiIds),
        )
    }

    /** Per watched POI, the deep links its host config supports: the web-app map
     *  page (`?poi=<id>`) and the Grafana availability grid. Sorted by id so a
     *  multi-POI watch renders deterministically. Empty when the watch has no
     *  POI-scoped targets; a POI still appears (with the null side dropped) when
     *  only one of the two hosts is configured. */
    private fun poiLinks(poiIds: Set<Long>): List<WatchStatusNotice.PoiLink> =
        poiIds.sorted().map { poiId ->
            WatchStatusNotice.PoiLink(
                poiId = poiId,
                mapUrl = appRootUrl?.let { "$it/?poi=$poiId" },
                gridUrl = grafanaRootUrl?.let { "$it/d/$CELL_MATRIX_UID?var-poi_id=$poiId" },
            )
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
