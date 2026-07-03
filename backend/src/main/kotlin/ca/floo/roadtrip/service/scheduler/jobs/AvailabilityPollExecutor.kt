package ca.floo.roadtrip.service.scheduler.jobs

import ca.floo.roadtrip.repo.AvailabilityCellRepo
import ca.floo.roadtrip.repo.AvailabilityFetchCallRepo
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.CatalogAvailabilityBatcher
import ca.floo.roadtrip.service.availability.FetchOutcome
import ca.floo.roadtrip.service.availability.GroupFetchResult
import ca.floo.roadtrip.service.availability.WatchScopeResolver
import ca.floo.roadtrip.service.availability.parentRefKey
import ca.floo.roadtrip.service.availability.toCatalogReservableRef
import ca.floo.roadtrip.service.ratelimit.VendorRateLimiter
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.scheduler.framework.HandlerResult
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.jooq.DSLContext
import org.jooq.impl.DSL
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
 * still reaching the future), derives the union polling window and the
 * tightest cadence across them, resolves each live watch's reservables to
 * [ca.floo.roadtrip.service.availability.ResolvedAvailabilityTarget]s
 * (filtered to this poller's own (provider, parentRef)), and hands them to
 * [CatalogAvailabilityBatcher] which groups by (provider, parentRef,
 * dateContext) into ONE upstream call per campground — not one call per site
 * and not one call per watch.
 *
 * If the poller has no live watches (every linked watch has fully elapsed),
 * the tick is the reaper: it retires the poller (marks elapsed watches done,
 * drops links, deactivates the poller) and makes no upstream call and writes
 * no run row.
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
    private val ctx: DSLContext,
    private val pollers: AvailabilityPollerRepo,
    private val reservablesRepo: ReservableRepo,
    private val batcher: CatalogAvailabilityBatcher,
    private val cells: AvailabilityCellRepo,
    private val runs: AvailabilityRunRepo,
    private val dateResolver: AvailabilityDateResolver,
    private val targets: AvailabilityTargetResolver,
    private val fetchCalls: AvailabilityFetchCallRepo,
    private val limiter: VendorRateLimiter,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scopeResolver = WatchScopeResolver(reservablesRepo)

    suspend fun handle(poller: AvailabilityPollerRepo.Poller): HandlerResult {
        val liveWatches = pollers.liveWatchesForPoller(poller.id)

        // Derive the union window across live watches; empty -> retire (the tick is the reaper).
        val minStart = liveWatches.minOfOrNull { it.startDate }
        val maxEnd = liveWatches.maxOfOrNull { it.endDate }
        if (liveWatches.isEmpty() || minStart == null || maxEnd == null) {
            // Empty window for the whole poller: every linked watch is elapsed. Mark them
            // done, drop links, deactivate the poller. No fetch, no run row.
            pollers.retire(poller.id, elapsedWatchIds = pollers.watchIdsForPoller(poller.id))
            return HandlerResult(nextRunAt = OffsetDateTime.now()) // inert; poller now inactive
        }

        // Cadence = tightest (min) over live watches, each watch resolving its own
        // three-level fall-through: watch.cadence_sec ?? poi.cadence_override_sec ??
        // GLOBAL_DEFAULT_SEC. The poi override is resolved once against the poller's
        // *representative* poi_id (a poller has one cadence and one representative POI),
        // not per watch-target — see AvailabilityPollerRepo.cadenceOverrideForPoller.
        val poiCadenceOverrideSec = pollers.cadenceOverrideForPoller(poller.id)
        val cadenceSec = resolveCadenceSec(liveWatches, poiCadenceOverrideSec)

        val resolved =
            liveWatches
                .flatMap { w -> scopeResolver.resolve(w).mapNotNull { targets.resolve(it) } }
                .filter {
                    parentRefKey(it.parentRef) == poller.parentRef &&
                        it.provider.id.name
                            .lowercase() == poller.provider
                }
                // Coalesced watches sharing this poller can resolve the same
                // reservable more than once; dedupe so a site is fetched once.
                .distinctBy { it.reservable.id }

        // The polling window per date-context. Shared by the governor token
        // count below and the batcher fetch, so the "skip null-window groups"
        // decision is made once and never drifts between the two.
        val windowFor: (
            ca.floo.roadtrip.models.availability.PoiDateContext,
            ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities,
        ) -> ca.floo.roadtrip.models.availability.ResolvedDateWindow? = { context, caps ->
            dateResolver.resolvePollingWindow(
                startDate = minStart,
                endDate = maxEnd,
                context = context,
                bookingHorizonDays = caps.bookingHorizonDays,
                maxDays = MAX_POLL_WINDOW_DAYS,
            )
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
                        fetch = { parentRef, provider, rows, window ->
                            provider.catalogAvailability(
                                CatalogAvailabilityRequest(
                                    ref = parentRef,
                                    reservables = rows.map { it.toCatalogReservableRef() },
                                    startDate = window.startDate,
                                    endDate = window.endDate,
                                    force = true,
                                ),
                            )
                        },
                    )
                val failure = results.firstOrNull { it.outcome != FetchOutcome.OK }
                val snapshotCount = results.sumOf { writeCube(it, runId) }
                // Dates that quietly aged out of a still-live poller's window reach
                // their terminal 'past' state here (belt-and-suspenders alongside PR1's
                // window-clamp retirement, which stops polling but does not flip the cell).
                val observedReservableIds =
                    results.flatMap { r -> r.reservables.map { it.id } }.distinct()
                cells.markElapsedAsPast(observedReservableIds, LocalDate.now(ZoneOffset.UTC))
                recordFetchCalls(results, runId)
                val completedAt = OffsetDateTime.now()
                val durationMs = durationMs(startedAt, completedAt)
                if (failure != null) {
                    runFailed = true
                    runs.fail(runId, error = failure.outcome.name.lowercase(), completedAt = completedAt, durationMs = durationMs)
                } else {
                    runs.complete(runId, snapshotCount, completedAt, durationMs)
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

    /** Cube write for one fetch group. Upserts every observed cell (liveness bump
     *  always; status/last_changed_at only on change), then appends an
     *  availability_snapshot transition row ONLY for cells whose status changed
     *  from the prior stored value. Returns the transition count for
     *  run.snapshot_count. Replaces PR1's append-every-observation appendSnapshots.
     *
     *  The cell upsert and the snapshot append run in ONE transaction (fresh
     *  txn-scoped repos over `DSL.using(config)`). Atomicity is load-bearing for
     *  the edge model: the cell holds the "seen" status, and a transition row is
     *  written only when that status *changes*. If the append failed after the
     *  cell already advanced, the next poll would see "no change" and never write
     *  the missing transition — the edge would be lost forever. Rolling the cell
     *  upsert back on append failure keeps the prior status stored, so a
     *  subsequent successful poll re-detects the same edge and writes it. */
    private fun writeCube(
        result: GroupFetchResult,
        runId: Long,
    ): Int {
        val batch = result.batch ?: return 0
        val idByRid = result.reservables.associateBy({ it.rid.encode() }, { it.id })
        val cellObservations =
            batch.observations.mapNotNull { obs ->
                val dbId = idByRid[obs.reservableId] ?: return@mapNotNull null
                AvailabilityCellRepo.CellObservation(
                    reservableId = dbId,
                    targetDate = obs.date,
                    status = obs.status,
                    observedAt = obs.observedAt,
                )
            }
        if (cellObservations.isEmpty()) return 0
        return ctx.transactionResult { config ->
            val txn = DSL.using(config)
            val changedKeys =
                AvailabilityCellRepo(txn)
                    .upsertObservations(cellObservations)
                    .filter { it.changed }
                    .map { it.reservableId to it.targetDate }
                    .toSet()
            val snapshotObservations =
                batch.observations.mapNotNull { obs ->
                    val dbId = idByRid[obs.reservableId] ?: return@mapNotNull null
                    if ((dbId to obs.date) !in changedKeys) return@mapNotNull null
                    AvailabilitySnapshotRepo.SnapshotObservation(
                        reservableId = dbId,
                        reservableRid = obs.reservableId,
                        targetDate = obs.date,
                        observedAt = obs.observedAt,
                        status = obs.status,
                    )
                }
            // A throw here (e.g. snapshot append failure) rolls back the cell upsert
            // above so the edge is re-detected on the next successful poll.
            AvailabilitySnapshotRepo(txn).appendObservations(
                AvailabilitySnapshotRepo.SnapshotObservationBatch(runId = runId, observations = snapshotObservations),
            )
        }
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

private const val MAX_POLL_WINDOW_DAYS = 60
private const val BACKOFF_BASE_MULTIPLIER = 2.0
private const val BACKOFF_CEILING_SEC = 3_600L // 1h cap on a wedged poller
private const val GLOBAL_DEFAULT_SEC = 300 // 5 min fall-through; PR4 layers poi override

// How soon a governor-starved poller retries. Short — the skip made no upstream
// call and did no work, so there is no backoff penalty to serve; we just want to
// re-check once the vendor bucket has likely refilled a token. Distinct from the
// success cadence and the failure backoff.
private const val GOVERNOR_STARVED_RETRY_SEC = 15L
