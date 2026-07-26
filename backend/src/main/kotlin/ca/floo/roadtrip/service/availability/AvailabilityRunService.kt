package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.CellTransition
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.observability.RoadtripMetrics
import ca.floo.roadtrip.repo.AvailabilityFetchCallRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

private const val BACKOFF_BASE_MULTIPLIER = 2.0
private const val BACKOFF_CEILING_SEC = 3_600L

// Mirrors the availability_run.status CHECK constraint (V16), so the metric
// label and the column can't drift.
private const val RUN_STATUS_COMPLETED = "completed"
private const val RUN_STATUS_FAILED = "failed"

internal class AvailabilityRunService(
    private val runRepo: AvailabilityRunRepo,
    private val availabilityRepo: AvailabilityRepo,
    private val fetchCallRepo: AvailabilityFetchCallRepo,
    private val metrics: RoadtripMetrics = RoadtripMetrics.NoOp,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class RunHandle(
        val runId: Long,
        val startedAt: OffsetDateTime,
    )

    sealed class RunOutcome {
        data class Completed(
            val transitions: List<CellTransition>,
        ) : RunOutcome()

        data class Failed(
            val error: String,
        ) : RunOutcome()
    }

    fun start(pollerId: Long): RunHandle {
        val startedAt = OffsetDateTime.now(clock)
        val runId = runRepo.start(pollerId, startedAt)
        return RunHandle(runId, startedAt)
    }

    fun recordResults(
        handle: RunHandle,
        results: List<GroupFetchResult>,
        attemptsByGroup: Map<Pair<BookingProvider, String>, List<FailoverAvailabilityFetcher.AttemptRecord>>,
    ): RunOutcome {
        val failure = results.firstOrNull { it.outcome != FetchOutcome.OK }
        val transitions = results.flatMap { writeObservations(it, handle.runId) }
        val observedCampsiteIds =
            results.flatMap { r -> r.campsites.map { it.id } }.distinct()
        availabilityRepo.markElapsedAsPast(observedCampsiteIds, LocalDate.now(clock))
        recordFetchCalls(results, attemptsByGroup, handle.runId)
        val completedAt = OffsetDateTime.now(clock)
        val durationMs = durationMs(handle.startedAt, completedAt)
        return if (failure != null) {
            runRepo.fail(handle.runId, error = failure.outcome.name.lowercase(), completedAt = completedAt, durationMs = durationMs)
            metrics.availabilityRunFinished(RUN_STATUS_FAILED, durationMs)
            RunOutcome.Failed(failure.outcome.name.lowercase())
        } else {
            runRepo.complete(handle.runId, transitions.size, completedAt, durationMs)
            metrics.availabilityRunFinished(RUN_STATUS_COMPLETED, durationMs)
            RunOutcome.Completed(transitions)
        }
    }

    fun failWithError(
        handle: RunHandle,
        error: String,
    ) {
        val completedAt = OffsetDateTime.now(clock)
        val durationMs = durationMs(handle.startedAt, completedAt)
        runRepo.fail(handle.runId, error = error, completedAt = completedAt, durationMs = durationMs)
        metrics.availabilityRunFinished(RUN_STATUS_FAILED, durationMs)
    }

    fun computeNextRunAt(
        pollerId: Long,
        cadenceSec: Int,
        failed: Boolean,
    ): OffsetDateTime =
        if (failed) {
            val failures = runRepo.countConsecutiveFailures(pollerId)
            val backoffSec = (cadenceSec * Math.pow(BACKOFF_BASE_MULTIPLIER, failures.toDouble())).toLong()
            OffsetDateTime.now(clock).plusSeconds(backoffSec.coerceAtMost(BACKOFF_CEILING_SEC))
        } else {
            OffsetDateTime.now(clock).plusSeconds(cadenceSec.toLong())
        }

    fun hasFreshCoverage(
        campsiteIds: List<Long>,
        startDate: LocalDate,
        endDate: LocalDate,
        freshAtOrAfter: OffsetDateTime,
    ): Boolean = availabilityRepo.hasFreshCoverage(campsiteIds, startDate, endDate, freshAtOrAfter)

    private fun writeObservations(
        result: GroupFetchResult,
        runId: Long,
    ): List<CellTransition> {
        val batch = result.batch ?: return emptyList()
        val campsiteIds = result.campsites.mapTo(mutableSetOf()) { it.id }
        val observations =
            batch.observations.mapNotNull { obs ->
                val dbId = obs.campsiteId?.takeIf { it in campsiteIds } ?: return@mapNotNull null
                AvailabilityRepo.Observation(
                    campsiteId = dbId,
                    targetDate = obs.date,
                    status = obs.status,
                    observedAt = obs.observedAt,
                )
            }
        if (observations.isEmpty()) return emptyList()
        return availabilityRepo.recordObservations(runId, observations)
    }

    private fun recordFetchCalls(
        results: List<GroupFetchResult>,
        attemptsByGroup: Map<Pair<BookingProvider, String>, List<FailoverAvailabilityFetcher.AttemptRecord>>,
        runId: Long,
    ) {
        results.filter { it.window != null }.forEach { r ->
            val refKey = r.parentRef?.let(::parentRefKey) ?: return@forEach
            val key = r.provider.id to refKey
            val attempts = attemptsByGroup[key].orEmpty()
            if (attempts.isEmpty()) {
                recordFetchCall(
                    runId = runId,
                    provider = r.provider.id,
                    parentRef = refKey,
                    campsiteCount = r.campsites.size,
                    window = r.window!!,
                    outcome = r.outcome,
                    durationMs = r.durationMs,
                    error = r.error,
                )
                return@forEach
            }
            attempts.forEach { attempt ->
                recordFetchCall(
                    runId = runId,
                    provider = attempt.provider,
                    parentRef = attempt.parentRef?.let(::parentRefKey) ?: refKey,
                    campsiteCount = r.campsites.size,
                    window = r.window!!,
                    outcome = attempt.outcome,
                    durationMs = attempt.durationMs,
                    error = attempt.error,
                )
            }
        }
    }

    /** Single write path for one upstream fetch, so the Postgres trace row and
     *  the Prometheus counter can never disagree about what happened. The row
     *  keeps `parent_ref` for drill-down; the metric deliberately drops it
     *  (unbounded cardinality) and keeps only provider + outcome. */
    private fun recordFetchCall(
        runId: Long,
        provider: BookingProvider,
        parentRef: String,
        campsiteCount: Int,
        window: ResolvedDateWindow,
        outcome: FetchOutcome,
        durationMs: Int?,
        error: String?,
    ) {
        val outcomeLabel = outcome.name.lowercase()
        fetchCallRepo.record(
            AvailabilityFetchCallRepo.NewCall(
                runId = runId,
                provider = provider.id,
                parentRef = parentRef,
                campsiteCount = campsiteCount,
                windowStart = window.startDate,
                windowEnd = window.endDate,
                outcome = outcomeLabel,
                durationMs = durationMs,
                error = error,
            ),
        )
        metrics.availabilityFetchCompleted(provider, outcomeLabel, durationMs)
    }

    fun nowUtc(): OffsetDateTime = OffsetDateTime.now(clock)

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
