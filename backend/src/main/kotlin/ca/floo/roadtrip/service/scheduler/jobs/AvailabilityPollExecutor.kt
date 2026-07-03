package ca.floo.roadtrip.service.scheduler.jobs

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
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.scheduler.framework.HandlerResult
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.OffsetDateTime

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
 * row. Successful runs are recorded as 'completed' with `snapshot_count`.
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
    private val snapshots: AvailabilitySnapshotRepo,
    private val runs: AvailabilityRunRepo,
    private val dateResolver: AvailabilityDateResolver,
    private val targets: AvailabilityTargetResolver,
    private val fetchCalls: AvailabilityFetchCallRepo,
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

        // Cadence = tightest (min) over live watches. cadence_sec is NOT NULL so the min
        // is well-defined; the GLOBAL_DEFAULT_SEC fall-through guards a non-positive value.
        val cadenceSec =
            liveWatches.minOf { it.cadenceSec }.let {
                if (it <= 0) GLOBAL_DEFAULT_SEC else it
            }

        val startedAt = OffsetDateTime.now()
        val runId = runs.start(poller.id, startedAt)
        var runFailed = false
        try {
            withContext(MDCContext(mapOf("run_id" to runId.toString()))) {
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
                val results =
                    batcher.fetchByGroup(
                        targets = resolved,
                        windowFor = { context, caps ->
                            dateResolver.resolvePollingWindow(
                                startDate = minStart,
                                endDate = maxEnd,
                                context = context,
                                bookingHorizonDays = caps.bookingHorizonDays,
                                maxDays = MAX_POLL_WINDOW_DAYS,
                            )
                        },
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
                val snapshotCount = results.sumOf { appendSnapshots(it, runId) }
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

    /** Append every observation the group returned as a snapshot row tagged with
     *  runId, mapping each observation's rid back to its catalog db id. */
    private fun appendSnapshots(
        result: GroupFetchResult,
        runId: Long,
    ): Int {
        val batch = result.batch ?: return 0
        val idByRid = result.reservables.associateBy({ it.rid.encode() }, { it.id })
        val observations =
            batch.observations.mapNotNull { obs ->
                val dbId = idByRid[obs.reservableId] ?: return@mapNotNull null
                AvailabilitySnapshotRepo.SnapshotObservation(
                    reservableId = dbId,
                    reservableRid = obs.reservableId,
                    targetDate = obs.date,
                    observedAt = obs.observedAt,
                    status = obs.status,
                )
            }
        return snapshots.appendObservations(
            AvailabilitySnapshotRepo.SnapshotObservationBatch(runId = runId, observations = observations),
        )
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

private const val MAX_POLL_WINDOW_DAYS = 60
private const val BACKOFF_BASE_MULTIPLIER = 2.0
private const val BACKOFF_CEILING_SEC = 3_600L // 1h cap on a wedged poller
private const val GLOBAL_DEFAULT_SEC = 300 // 5 min fall-through; PR4 layers poi override
