package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.CellTransition
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.observability.RoadtripMetrics
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.service.notification.common.NotificationSender
import ca.floo.roadtrip.service.notification.common.WatchOpening
import ca.floo.roadtrip.service.notification.common.WatchStatusNotice
import java.time.LocalDate

/**
 * The Grafana dashboard a watch alert deep-links to: the campground detail
 * board, parameterised by the watched POI (`var-poi_id`), which is where the
 * availability grid for that campground lives.
 *
 * The UID must match a dashboard provisioned in `grafana/dashboards/`;
 * `scripts/validate_grafana_dashboards.py` asserts that for every
 * `GRAFANA_*_UID` constant in this source set, so a rename on either side
 * fails the lint job instead of shipping a 404 to users.
 */
private const val GRAFANA_CAMPGROUND_DETAIL_UID = "campground-detail"

/**
 * Turns cube edges into watch alerts. Called once per poller run, after the
 * cube write, with the transitions that tick produced and the poller's live
 * watches (both already in the executor's hand).
 *
 * For each live watch it keeps the transitions that fall inside the watch's
 * campsite set and date window and — for every kind on the watch that has a
 * registered [TriggerActionHandler] — fires that handler with the hydrated
 * openings. Kinds with no registered handler stay inert.
 * A watch with `stopWhenTriggered` goes `DONE` **only after a handler
 * actually reports success**, so a delivery failure never silences a watch
 * we could not notify on.
 *
 * This class decides *which* watches fire and hydrates their openings; the
 * handlers own transport and formatting (the [NotifyTriggerActionHandler]
 * delegates to [NotificationSender], which no-ops when requested transports
 * are unconfigured).
 * Nothing here throws into the caller: handlers swallow their own failures
 * and the executor wraps this call best-effort.
 *
 * The notice-building helper [statusNoticeForWatch] is public so the Slack
 * interactivity handler can re-render a watch's card after a user pauses /
 * resumes / deletes it from the card itself, keeping the "which POI, which
 * dashboard URLs" logic in exactly one place.
 */
internal class WatchAlertDispatcher(
    private val notifications: NotificationSender,
    private val scopeResolver: WatchScopeResolver,
    private val watchRepo: AvailabilityWatchRepo,
    private val targetResolver: WatchNotificationTargetResolver,
    private val targets: AvailabilityTargetResolver,
    private val poiRepo: PoiServingRepo,
    private val availabilityRepo: AvailabilityRepo,
    private val triggerActions: TriggerActionRegistry,
    private val grafanaRootUrl: String?,
    private val appRootUrl: String?,
    private val metrics: RoadtripMetrics = RoadtripMetrics.NoOp,
) {
    suspend fun dispatch(
        liveWatches: List<AvailabilityWatchRepo.Watch>,
        transitions: List<CellTransition>,
    ) {
        val bookable = transitions.filter { it.status.isOnlineBookable }
        if (bookable.isEmpty()) return

        for (watch in liveWatches) {
            val handlers = triggerActions.forKinds(watch.triggerKinds)
            if (handlers.isEmpty()) continue
            val campsitesById = scopeResolver.resolve(watch).associateBy { it.id }
            val covered =
                bookable.filter { t ->
                    t.campsiteId in campsitesById && t.targetDate.withinWindow(watch)
                }
            if (covered.isEmpty()) continue
            postOpenings(watch, covered, campsitesById, handlers)
        }
    }

    /**
     * Initial trigger evaluation for a watch whose lifecycle just changed —
     * created, updated, or paused. Unlike [dispatch], which reacts to cube
     * *edges*, this inspects the current cube face, so a watch created on an
     * already-open site is not silently stranded (its openings pre-date any
     * future edge). Fire-and-forget from the route, so like [dispatch] it never
     * throws into its caller.
     *
     * A **paused/done** notification watch reports its lifecycle state and
     * stops — no availability lookup, never a trigger. An **active** watch reads
     * the current cube face for its window:
     *  - **some cells bookable** → the same openings alert [dispatch] sends; a
     *    real trigger, so `stopWhenTriggered` still marks the watch `DONE`.
     *  - **cells known, none bookable** → notification targets get informational
     *    "nothing open yet".
     *  - **no cells (cold POI)** → notification targets get informational "not
     *    checked yet"; the immediate poll `create()` schedules will observe the
     *    window and its first observation is itself an edge, so the real opening
     *    fires via [dispatch].
     * Only the bookable state ever marks a watch `DONE`.
     */
    suspend fun dispatchInitial(watch: AvailabilityWatchRepo.Watch) {
        val handlers = triggerActions.forKinds(watch.triggerKinds)
        val notificationTargets = targetResolver.resolve(watch)
        if (notificationTargets.isEmpty() && handlers.isEmpty()) return

        val campsites = scopeResolver.resolve(watch)
        if (watch.status != WatchStatus.ACTIVE) {
            if (notificationTargets.isEmpty()) return
            val state =
                when (watch.status) {
                    WatchStatus.PAUSED -> WatchStatusNotice.State.PAUSED
                    WatchStatus.DONE -> WatchStatusNotice.State.DONE
                    WatchStatus.ACTIVE -> WatchStatusNotice.State.WATCHING // unreachable; guarded above
                }
            notifications.sendWatchStatus(
                statusNotice(watch, campsites, state),
                targets = notificationTargets,
            )
            return
        }
        val campsitesById = campsites.associateBy { it.id }
        val cells = availabilityRepo.readCurrent(campsites.map { it.id }, datesInWindow(watch))
        val bookable = cells.filter { it.available }
        if (bookable.isNotEmpty()) {
            val covered = bookable.map { CellTransition(it.campsiteId, it.targetDate, it.status) }
            postOpenings(watch, covered, campsitesById, handlers)
        } else if (notificationTargets.isNotEmpty()) {
            val state = if (cells.isNotEmpty()) WatchStatusNotice.State.WATCHING else WatchStatusNotice.State.UNCHECKED
            notifications.sendWatchStatus(
                statusNotice(watch, campsites, state),
                targets = notificationTargets,
            )
        }
    }

    /**
     * The terminal "watch stopped" message for a notification watch the user
     * just deleted. Sent once, from the delete route, *before* the row is
     * removed (its scope is still resolvable). Like [dispatchInitial] it never
     * throws into its caller and no-ops for a watch with no notification target.
     */
    suspend fun dispatchStopped(watch: AvailabilityWatchRepo.Watch) {
        val notificationTargets = targetResolver.resolve(watch)
        if (notificationTargets.isEmpty()) return
        val campsites = scopeResolver.resolve(watch)
        notifications.sendWatchStatus(
            statusNotice(watch, campsites, WatchStatusNotice.State.STOPPED),
            targets = notificationTargets,
        )
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

    /** Hydrates the covered cells into [TriggerOpening]s, fires each of the
     *  watch's registered [TriggerActionHandler]s with them, and — if any
     *  handler reports success — marks a `stopWhenTriggered` watch `DONE`, so
     *  a delivery failure never silences a watch we could not notify on.
     *  Shared by the edge and initial paths. Handlers own transport and
     *  formatting; this method only hydrates and gates the DONE transition. */
    private suspend fun postOpenings(
        watch: AvailabilityWatchRepo.Watch,
        covered: List<CellTransition>,
        campsitesById: Map<Long, Campsite>,
        handlers: List<TriggerActionHandler>,
    ) {
        if (handlers.isEmpty()) return
        val openings = hydrateOpenings(covered, campsitesById)
        // Fire every registered handler; fold-any so one succeeding handler is
        // enough to satisfy `stopWhenTriggered`. `.any { it }` on a mapped
        // list evaluates every handler (no short-circuit), matching the
        // "each handler is invoked exactly once per trigger" contract.
        val fired =
            handlers
                .map { handler ->
                    handler.fire(watch, openings).also { delivered ->
                        // The whole point of a watch: it is not enough that the
                        // opening was found, the user has to have been told.
                        metrics.watchTriggerFired(handler.kinds, delivered)
                    }
                }.any { it }
        if (fired && watch.stopWhenTriggered) {
            watchRepo.update(watch.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.DONE))
        }
    }

    /** Resolves each covered cell to a [TriggerOpening] — the campsite's display
     *  label/loop/type, parent campground, provider booking URL, and resolved
     *  provider candidates — so handlers can project either notification or
     *  booking inputs from one hydration pass. */
    private fun hydrateOpenings(
        covered: List<CellTransition>,
        campsitesById: Map<Long, Campsite>,
    ): List<TriggerOpening> {
        val poiNames = HashMap<Long, String?>()
        return covered.map { t ->
            // covered was filtered to campsites in this map, so the key exists.
            val r = campsitesById.getValue(t.campsiteId)
            val target = targets.resolve(r)
            TriggerOpening(
                campsite = r,
                date = t.targetDate,
                resolvedTarget = target,
                watchOpening =
                    WatchOpening(
                        label = r.displayName(),
                        loop = r.loopName,
                        siteType = r.kind,
                        date = t.targetDate,
                        campgroundId = target?.parentPoiId,
                        campground = target?.parentPoiId?.let { poiNames.getOrPut(it) { poiRepo.fetchPoiName(it) } },
                        // Booking link, if the campsite's provider exposes one — the URL
                        // scheme is the adapter's, never this dispatcher's. The parent
                        // ref supplies vendor ids the per-site ref may omit (e.g. Aspira).
                        bookingUrl =
                            target?.let { tgt ->
                                tgt.parentRef?.let { ref -> tgt.provider.reservationUrl(r, ref, t.targetDate) }
                            },
                        vendor =
                            target
                                ?.provider
                                ?.id
                                ?.name
                                ?.lowercase()
                                ?: r.catalogVendor().lowercase(),
                    ),
            )
        }
    }

    /** Builds the plain-data [WatchStatusNotice] for a watch's lifecycle/status
     *  message. Carries the watch id (echoed as every interactive button's
     *  value), scope, window, and per-POI deep links (the web-app map page +
     *  the Grafana availability grid); the notification
     *  layer owns the Block Kit rendering. A single-campsite watch reports
     *  its site name (+ loop); a broader one reports the count. POI links come
     *  from the watch's POI-scoped targets (campsite-scoped targets carry
     *  no POI). */
    private fun statusNotice(
        watch: AvailabilityWatchRepo.Watch,
        campsites: List<Campsite>,
        state: WatchStatusNotice.State,
    ): WatchStatusNotice {
        val poiIds = watch.targets.mapNotNull { it.poiId }.toSet()
        // A POI-scoped watch is "the campground" even when it expands to one
        // site; a campsite-scoped watch names the site. So the scope label
        // keys off the target kind, not the resolved campsite count.
        val siteScoped = poiIds.isEmpty()
        val single = campsites.singleOrNull().takeIf { siteScoped }
        return WatchStatusNotice(
            watchId = watch.id,
            state = state,
            siteCount = campsites.size,
            siteName = single?.displayName(),
            siteLoop = single?.loopName,
            campgroundName = poiIds.singleOrNull()?.let { poiRepo.fetchPoiName(it) },
            startDate = watch.startDate,
            endDate = watch.endDate,
            poiLinks = poiLinks(poiIds),
            appRootUrl = appRootUrl,
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
                gridUrl = grafanaRootUrl?.let { "$it/d/$GRAFANA_CAMPGROUND_DETAIL_UID?var-poi_id=$poiId" },
            )
        }

    /** The half-open [startDate, endDate) day list — the same window contract
     *  [withinWindow] enforces on the edge path — for reading the current cube. */
    private fun datesInWindow(watch: AvailabilityWatchRepo.Watch): List<LocalDate> =
        AvailabilityWatchDateWindow.datesIn(watch.startDate, watch.endDate)
}

// The watch window is half-open [startDate, endDate) — the same contract the
// provider fetch and current-state reads use. endDate is the checkout day, not a
// watched night, so it is excluded: with coalesced pollers a longer watch can
// pull a transition on a shorter watch's endDate into the shared fetch, and an
// inclusive bound would misfire the shorter watch (and wrongly mark it done).
private fun LocalDate.withinWindow(watch: AvailabilityWatchRepo.Watch): Boolean = !isBefore(watch.startDate) && isBefore(watch.endDate)
