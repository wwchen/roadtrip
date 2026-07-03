package ca.floo.roadtrip.service.scheduler.jobs

import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.repo.AvailabilityFetchCallRepo
import ca.floo.roadtrip.repo.AvailabilityJobRepo
import ca.floo.roadtrip.repo.AvailabilityJobRunRepo
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.CatalogAvailabilityBatcher
import ca.floo.roadtrip.service.availability.FetchOutcome
import ca.floo.roadtrip.service.availability.GroupFetchResult
import ca.floo.roadtrip.service.availability.ResolvedAvailabilityTarget
import ca.floo.roadtrip.service.availability.WatchScopeResolver
import ca.floo.roadtrip.service.availability.toCatalogReservableRef
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.scheduler.framework.HandlerResult
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Executes one polling job. Wired into [Scheduler] as the handler.
 *
 * Both reservable-scope and POI-scope intents resolve to a list of
 * [ResolvedAvailabilityTarget] (POI-scope fans out to child reservables via
 * [WatchScopeResolver]), which [CatalogAvailabilityBatcher] then groups by
 * (provider, parentRef, dateContext) into ONE upstream call per campground
 * — not one call per site.
 *
 * Per-run audit: every invocation writes one [AvailabilityJobRunRepo]
 * row. Successful runs are recorded as 'completed' with `snapshot_count`.
 * If any group's fetch did not come back OK (rate limited, blocked,
 * upstream 5xx, other), the run is recorded as 'failed' with that
 * outcome as the error string. Upstream / unexpected exceptions are
 * recorded as 'failed' with the exception message. Runs are never lost —
 * even if `start` succeeds and the work errors, the row gets a terminal
 * status so the operator can see the failure.
 *
 * Handler always returns a [HandlerResult] — even on upstream failure —
 * because losing the row would mean the watch silently stops polling.
 */
internal class AvailabilityPollExecutor(
    private val reservablesRepo: ReservableRepo,
    private val batcher: CatalogAvailabilityBatcher,
    private val snapshots: AvailabilitySnapshotRepo,
    private val runs: AvailabilityJobRunRepo,
    private val dateResolver: AvailabilityDateResolver,
    private val targets: AvailabilityTargetResolver,
    private val fetchCalls: AvailabilityFetchCallRepo,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scopeResolver = WatchScopeResolver(reservablesRepo)

    suspend fun handle(job: AvailabilityJobRepo.Job): HandlerResult {
        val startedAt = OffsetDateTime.now()
        val runId = runs.start(job.id, startedAt)
        try {
            val intent = AvailabilityJobIntent.fromJsonObject(job.intentPayload)
            val resolved = resolveTargets(intent)
            val results =
                batcher.fetchByGroup(
                    targets = resolved,
                    windowFor = { context, caps ->
                        dateResolver.resolvePollingWindow(
                            startDate = LocalDate.parse(intent.startDate),
                            endDate = LocalDate.parse(intent.endDate),
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
                runs.fail(runId, error = failure.outcome.name.lowercase(), completedAt = completedAt, durationMs = durationMs)
            } else {
                runs.complete(runId, snapshotCount, completedAt, durationMs)
            }
        } catch (e: Exception) {
            log.warn("job {} run {} failed: {}", job.id, runId, e.message)
            val completedAt = OffsetDateTime.now()
            runs.fail(
                runId,
                error = e.message ?: e::class.simpleName ?: "unknown",
                completedAt = completedAt,
                durationMs = durationMs(startedAt, completedAt),
            )
        }
        return HandlerResult(nextRunAt = OffsetDateTime.now().plusSeconds(job.cadenceSec.toLong()))
    }

    /** Resolve an intent to the reservables we will poll, each carrying its
     *  provider target. POI-scope fans out to child reservables here (in the
     *  poller), but the fan-out becomes ONE grouped upstream call in the batcher. */
    private fun resolveTargets(intent: AvailabilityJobIntent): List<ResolvedAvailabilityTarget> =
        when (intent) {
            is AvailabilityJobIntent.Reservable ->
                reservablesRepo
                    .findById(intent.reservableId)
                    ?.let { targets.resolve(it) }
                    ?.let(::listOf)
                    .orEmpty()
            is AvailabilityJobIntent.Poi ->
                scopeResolver.resolve(intent).mapNotNull { targets.resolve(it) }
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

    /** Renders a vendor's call-unit id as text for observability. Not
     *  provider dispatch — just formatting a value already picked by the
     *  batcher's grouping key, so this `when` is not a capability leak. */
    private fun parentRefKey(ref: ProviderRef): String =
        when (ref) {
            is ProviderRef.RecGov -> ref.recgovId
            is ProviderRef.Aspira -> ref.mapId.toString()
            is ProviderRef.ReserveAmerica -> ref.parkId
            is ProviderRef.ReserveCalifornia -> ref.facilityIds.joinToString(",")
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
