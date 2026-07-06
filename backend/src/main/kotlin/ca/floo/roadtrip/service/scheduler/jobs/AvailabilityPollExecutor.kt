package ca.floo.roadtrip.service.scheduler.jobs

import ca.floo.roadtrip.models.availability.CellTransition
import ca.floo.roadtrip.models.domain.ReservableType
import ca.floo.roadtrip.repo.AvailabilityFetchCallRepo
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.CatalogAvailabilityBatcher
import ca.floo.roadtrip.service.availability.FetchOutcome
import ca.floo.roadtrip.service.availability.GroupFetchResult
import ca.floo.roadtrip.service.availability.WatchAlertDispatcher
import ca.floo.roadtrip.service.availability.parentRefKey
import ca.floo.roadtrip.service.availability.toCatalogReservableRef
import ca.floo.roadtrip.service.ratelimit.VendorRateLimiter
import ca.floo.roadtrip.service.scheduler.framework.HandlerResult
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Executes one poller tick. Wired into [ca.floo.roadtrip.service.scheduler.framework.Scheduler]
 * as the handler.
 *
 * A poller is the coalesced, per-(provider, parent_ref) unit of scheduled
 * work. The executor loads the poller's **live watches** (active, window
 * still reaching the future) only to decide *whether* to run (a live watch is
 * the reference that keeps the poller alive) and to derive the tightest
 * cadence across them. Neither the fetch window nor the fetch target set is
 * derived from watch state: the polling window is vendor-derived and anchored
 * at today (the widest window the upstream exposes per tick), and the target
 * set is the poller's parent campground's **full catalog** — every child
 * reservable under its representative POI, not just the sites a watch happens
 * to reference. It resolves that catalog to
 * [ca.floo.roadtrip.service.availability.ResolvedAvailabilityTarget]s
 * (filtered to this poller's own (provider, parentRef)), and hands them to
 * [CatalogAvailabilityBatcher] which groups by (provider, parentRef,
 * dateContext) into ONE upstream call per campground — not one call per site
 * and not one call per watch.
 *
 * If the poller has no live watches (every linked watch has fully elapsed),
 * the tick makes no upstream call and writes no run row — it just reschedules.
 * Teardown (marking watches done, dropping links, deactivating the poller) is
 * owned by [ca.floo.roadtrip.service.scheduler.WatchReaper], not the fetch
 * path; the tick never mutates watch or poller lifecycle.
 *
 * Per-run audit: every invocation that fetches writes one [AvailabilityRunRepo]
 * row. Successful runs are recorded as 'completed' with `snapshot_count` -- which
 * under the cube model counts edge-triggered transitions (status changes), not raw
 * observations.
 * If any group's fetch did not come back OK (rate limited, blocked,
 * upstream 5xx, other), the run is recorded as 'failed' with that
 * outcome as the error string. Upstream / unexpected exceptions are
 * recorded as 'failed' with the exception message. Runs are never lost —
 * even if `start` succeeds and the work errors, the row gets a terminal
 * status so the operator can see the failure.
 *
 * Handler always returns a [HandlerResult] — even on upstream failure —
 * because losing the row would mean the poller silently stops polling.
 */
internal class AvailabilityPollExecutor(
    private val pollers: AvailabilityPollerRepo,
    private val reservablesRepo: ReservableRepo,
    private val batcher: CatalogAvailabilityBatcher,
    private val availability: AvailabilityRepo,
    private val runs: AvailabilityRunRepo,
    private val dateResolver: AvailabilityDateResolver,
    private val targets: AvailabilityTargetResolver,
    private val fetchCalls: AvailabilityFetchCallRepo,
    private val limiter: VendorRateLimiter,
    private val alertDispatcher: WatchAlertDispatcher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun handle(poller: AvailabilityPollerRepo.Poller): HandlerResult {
        val liveWatches = pollers.liveWatchesForPoller(poller.id)

        // A live watch gates *whether* this poller runs, never *how wide* it
        // fetches (that's vendor-derived below). No live watch -> nothing to
        // serve: skip the fetch. The tick does NOT mutate — elapsed-watch
        // teardown (mark done, drop links, deactivate) belongs to WatchReaper
        // now, not the fetch path. Reschedule on the idle cadence; the reaper
        // deactivates this poller shortly so it stops being claimed.
        if (liveWatches.isEmpty()) {
            return HandlerResult(nextRunAt = OffsetDateTime.now().plusSeconds(IDLE_RESCHEDULE_SEC))
        }

        // Cadence = tightest (min) over live watches, each watch resolving its own
        // three-level fall-through: watch.cadence_sec ?? poi.cadence_override_sec ??
        // GLOBAL_DEFAULT_SEC. The poi override is resolved once against the poller's
        // *representative* poi_id (a poller has one cadence and one representative POI),
        // not per watch-target — see AvailabilityPollerRepo.cadenceOverrideForPoller.
        val poiCadenceOverrideSec = pollers.cadenceOverrideForPoller(poller.id)
        val cadenceSec = resolveCadenceSec(liveWatches, poiCadenceOverrideSec)

        // Fetch the parent campground's FULL catalog — every child reservable
        // under the poller's representative POI — not just the sites some live
        // watch happens to reference. One catalogAvailability call returns the
        // whole parent grid regardless of the reservable list (the list is a
        // projection, not a call multiplier), so widening to the full catalog
        // costs no extra upstream calls and maximally widens snapshot history.
        // This is what severs the fetch path from watch state: the poller reads
        // no watch to decide *what* to fetch — only whether to run (above) and
        // how often (cadence).
        val resolved =
            reservablesRepo
                .findByPoi(poller.poiId, type = ReservableType.SITE)
                .mapNotNull { targets.resolve(it) }
                .filter {
                    parentRefKey(it.parentRef) == poller.parentRef &&
                        it.provider.id.name
                            .lowercase() == poller.provider
                }
                // findByPoi returns distinct reservables, but guard against
                // duplicate poi links so a site is fetched once.
                .distinctBy { it.reservable.id }

        // The polling window per date-context: the widest window the vendor
        // exposes for a single tick, anchored at today and independent of the
        // watches' own dates. Shared by the governor token count below and the
        // batcher fetch, so the "skip null-window groups" decision is made once
        // and never drifts between the two.
        val windowFor: (
            ca.floo.roadtrip.models.availability.PoiDateContext,
            ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities,
        ) -> ca.floo.roadtrip.models.availability.AvailabilityWindows? = { context, caps ->
            val resolvedWindow =
                dateResolver.resolvePollingWindow(
                    context = context,
                    maxPollWindowDays = caps.maxPollWindowDays,
                    bookingHorizonDays = caps.bookingHorizonDays,
                )
            resolvedWindow?.let {
                ca.floo.roadtrip.models.availability
                    .AvailabilityWindows(target = it, fetch = it)
            }
        }

        // Vendor governor: acquire one token per (provider, parentRef, dateContext)
        // group the batcher will actually FETCH, BEFORE any upstream call. Groups
        // whose polling window is null (all target dates elapsed) are skipped by
        // the batcher — no upstream call — so they are excluded from the count;
        // charging a token for a non-fetch would waste it and could starve a
        // bucket, delaying retirement of an all-elapsed poller. On starvation,
        // skip the fetch entirely — no upstream call, no wasted 429, and no run
        // row (a starved tick is a non-event, like an empty window, not a
        // failure, so it never feeds consecutive-failure backoff). Reschedule soon
        // so the poller retries once the bucket refills. Per-poller backoff stays
        // the reactive net for real upstream failures.
        val bucketCount = batcher.countFetchGroups(resolved, windowFor)
        if (bucketCount > 0 && !limiter.tryAcquire(poller.provider, bucketCount.toLong())) {
            log.info(
                "poller {} governor starved ({} tokens for {}); rescheduling in {}s",
                poller.id,
                bucketCount,
                poller.provider,
                GOVERNOR_STARVED_RETRY_SEC,
            )
            return HandlerResult(nextRunAt = OffsetDateTime.now().plusSeconds(GOVERNOR_STARVED_RETRY_SEC))
        }

        val startedAt = OffsetDateTime.now()
        val runId = runs.start(poller.id, startedAt)
        var runFailed = false
        try {
            withContext(MDCContext(mapOf("run_id" to runId.toString()))) {
                val results =
                    batcher.fetchByGroup(
                        targets = resolved,
                        windowFor = windowFor,
                        fetch = { parentRef, provider, rows, windows ->
                            provider.catalogAvailability(
                                ref = parentRef,
                                reservables = rows.map { it.toCatalogReservableRef() },
                                startDate = windows.fetch.startDate,
                                endDate = windows.fetch.endDate,
                            )
                        },
                    )
                val failure = results.firstOrNull { it.outcome != FetchOutcome.OK }
                val transitions = results.flatMap { writeCube(it, runId) }
                val snapshotCount = transitions.size
                // Dates that quietly aged out of a still-live poller's window reach
                // their terminal 'past' state here (belt-and-suspenders alongside PR1's
                // window-clamp retirement, which stops polling but does not flip the cell).
                val observedReservableIds =
                    results.flatMap { r -> r.reservables.map { it.id } }.distinct()
                availability.markElapsedAsPast(observedReservableIds, LocalDate.now(ZoneOffset.UTC))
                recordFetchCalls(results, runId)
                val completedAt = OffsetDateTime.now()
                val durationMs = durationMs(startedAt, completedAt)
                if (failure != null) {
                    runFailed = true
                    runs.fail(runId, error = failure.outcome.name.lowercase(), completedAt = completedAt, durationMs = durationMs)
                } else {
                    runs.complete(runId, snapshotCount, completedAt, durationMs)
                    // Alert on the edges this tick produced (best-effort: a
                    // notification failure is logged, never fails the run or
                    // trips backoff). liveWatches + transitions are already in
                    // hand — no re-scan of the cube.
                    runCatching { alertDispatcher.dispatch(liveWatches, transitions) }
                        .onFailure { log.warn("poller {} run {} alert dispatch failed: {}", poller.id, runId, it.message) }
                }
            }
        } catch (e: Exception) {
            log.warn("poller {} run {} failed: {}", poller.id, runId, e.message)
            runFailed = true
            val completedAt = OffsetDateTime.now()
            runs.fail(
                runId,
                error = e.message ?: e::class.simpleName ?: "unknown",
                completedAt = completedAt,
                durationMs = durationMs(startedAt, completedAt),
            )
        }
        val nextRunAt =
            if (runFailed) {
                // countConsecutiveFailures includes the run we just failed (its terminal
                // status was already written above), so failures >= 1 here.
                val failures = runs.countConsecutiveFailures(poller.id)
                val backoffSec = (cadenceSec * Math.pow(BACKOFF_BASE_MULTIPLIER, failures.toDouble())).toLong()
                OffsetDateTime.now().plusSeconds(backoffSec.coerceAtMost(BACKOFF_CEILING_SEC))
            } else {
                OffsetDateTime.now().plusSeconds(cadenceSec.toLong())
            }
        return HandlerResult(nextRunAt = nextRunAt)
    }

    /** Record one fetch group's observations into the availability interval table.
     *  [AvailabilityRepo.recordObservations] bumps unchanged cells in place and
     *  inserts a new status-run only on a change, returning one [CellTransition]
     *  per change in a single transaction. The caller sums them for
     *  run.snapshot_count and hands the online-bookable subset to the alert
     *  dispatcher — replacing the old two-table cube+snapshot write. */
    private fun writeCube(
        result: GroupFetchResult,
        runId: Long,
    ): List<CellTransition> {
        val batch = result.batch ?: return emptyList()
        val idByRid = result.reservables.associateBy({ it.rid.encode() }, { it.id })
        val observations =
            batch.observations.mapNotNull { obs ->
                val dbId = idByRid[obs.reservableId] ?: return@mapNotNull null
                AvailabilityRepo.Observation(
                    reservableId = dbId,
                    targetDate = obs.date,
                    status = obs.status,
                    observedAt = obs.observedAt,
                )
            }
        if (observations.isEmpty()) return emptyList()
        return availability.recordObservations(runId, observations)
    }

    /** Write one trace row per group that made a real upstream call (window
     *  != null). Null-window groups were skipped by the batcher and made no
     *  call, so they leave no trace. Written for every outcome — a rate
     *  limited or failed group still produced a call worth tracing. */
    private fun recordFetchCalls(
        results: List<GroupFetchResult>,
        runId: Long,
    ) {
        results.filter { it.window != null }.forEach { r ->
            val providerId = r.provider.id.name
            fetchCalls.record(
                AvailabilityFetchCallRepo.NewCall(
                    runId = runId,
                    provider = providerId.lowercase(),
                    parentRef = parentRefKey(r.parentRef),
                    reservableCount = r.reservables.size,
                    windowStart = r.window!!.startDate,
                    windowEnd = r.window.endDate,
                    outcome = r.outcome.name.lowercase(),
                    durationMs = r.durationMs,
                    error = r.error,
                ),
            )
        }
    }

    private fun durationMs(
        start: OffsetDateTime,
        end: OffsetDateTime,
    ): Int =
        Duration
            .between(start, end)
            .toMillis()
            .toInt()
            .coerceAtLeast(0)
}

/**
 * Resolves the poller's cadence as the tightest (min) over live watches, where
 * each watch resolves the spec's three-level fall-through:
 * `watch.cadence_sec ?? poi.cadence_override_sec ?? GLOBAL_DEFAULT_SEC`.
 *
 * A watch's `cadenceSec` is a NULLABLE desired override: NULL means "no
 * watch-level preference," so the rung falls through to the POI override, then
 * the global default. [poiCadenceOverrideSec] is the override of the poller's
 * *representative* POI — a poller has one cadence and one representative POI, so
 * the override is a single per-poller rung rather than a per-watch-target lookup.
 */
internal fun resolveCadenceSec(
    liveWatches: List<ca.floo.roadtrip.repo.AvailabilityWatchRepo.Watch>,
    poiCadenceOverrideSec: Int?,
): Int {
    val resolved =
        liveWatches.map { w ->
            w.cadenceSec
                ?: poiCadenceOverrideSec
                ?: GLOBAL_DEFAULT_SEC
        }
    return resolved.minOrNull() ?: GLOBAL_DEFAULT_SEC
}

// How soon a poller with no live watches re-checks. It made no upstream call
// and did no work — WatchReaper will deactivate it shortly — so this is just a
// low-frequency holding cadence, not the success cadence.
private const val IDLE_RESCHEDULE_SEC = 300L

private const val BACKOFF_BASE_MULTIPLIER = 2.0
private const val BACKOFF_CEILING_SEC = 3_600L // 1h cap on a wedged poller
private const val GLOBAL_DEFAULT_SEC = 300 // 5 min fall-through; PR4 layers poi override

// How soon a governor-starved poller retries. Short — the skip made no upstream
// call and did no work, so there is no backoff penalty to serve; we just want to
// re-check once the vendor bucket has likely refilled a token. Distinct from the
// success cadence and the failure backoff.
private const val GOVERNOR_STARVED_RETRY_SEC = 15L
