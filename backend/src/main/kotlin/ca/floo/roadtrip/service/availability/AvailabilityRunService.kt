package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.CellTransition
import ca.floo.roadtrip.repo.AvailabilityFetchCallRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

private const val BACKOFF_BASE_MULTIPLIER = 2.0
private const val BACKOFF_CEILING_SEC = 3_600L

internal class AvailabilityRunService(
    private val runs: AvailabilityRunRepo,
    private val availability: AvailabilityRepo,
    private val fetchCalls: AvailabilityFetchCallRepo,
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
        val runId = runs.start(pollerId, startedAt)
        return RunHandle(runId, startedAt)
    }

    fun recordResults(
        handle: RunHandle,
        results: List<GroupFetchResult>,
        attemptsByGroup: Map<Pair<AvailabilityProviderId, String>, List<FailoverAvailabilityFetcher.AttemptRecord>>,
    ): RunOutcome {
        val failure = results.firstOrNull { it.outcome != FetchOutcome.OK }
        val transitions = results.flatMap { writeObservations(it, handle.runId) }
        val observedCampsiteIds =
            results.flatMap { r -> r.campsites.map { it.id } }.distinct()
        availability.markElapsedAsPast(observedCampsiteIds, LocalDate.now(clock))
        recordFetchCalls(results, attemptsByGroup, handle.runId)
        val completedAt = OffsetDateTime.now(clock)
        val durationMs = durationMs(handle.startedAt, completedAt)
        return if (failure != null) {
            runs.fail(handle.runId, error = failure.outcome.name.lowercase(), completedAt = completedAt, durationMs = durationMs)
            RunOutcome.Failed(failure.outcome.name.lowercase())
        } else {
            runs.complete(handle.runId, transitions.size, completedAt, durationMs)
            RunOutcome.Completed(transitions)
        }
    }

    fun failWithError(
        handle: RunHandle,
        error: String,
    ) {
        val completedAt = OffsetDateTime.now(clock)
        runs.fail(handle.runId, error = error, completedAt = completedAt, durationMs = durationMs(handle.startedAt, completedAt))
    }

    fun computeNextRunAt(
        pollerId: Long,
        cadenceSec: Int,
        failed: Boolean,
    ): OffsetDateTime =
        if (failed) {
            val failures = runs.countConsecutiveFailures(pollerId)
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
    ): Boolean = availability.hasFreshCoverage(campsiteIds, startDate, endDate, freshAtOrAfter)

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
        return availability.recordObservations(runId, observations)
    }

    private fun recordFetchCalls(
        results: List<GroupFetchResult>,
        attemptsByGroup: Map<Pair<AvailabilityProviderId, String>, List<FailoverAvailabilityFetcher.AttemptRecord>>,
        runId: Long,
    ) {
        results.filter { it.window != null }.forEach { r ->
            val key = r.provider.id to parentRefKey(r.parentRef)
            val attempts = attemptsByGroup[key].orEmpty()
            if (attempts.isEmpty()) {
                fetchCalls.record(
                    AvailabilityFetchCallRepo.NewCall(
                        runId = runId,
                        provider =
                            r.provider.id.name
                                .lowercase(),
                        parentRef = parentRefKey(r.parentRef),
                        campsiteCount = r.campsites.size,
                        windowStart = r.window!!.startDate,
                        windowEnd = r.window.endDate,
                        outcome = r.outcome.name.lowercase(),
                        durationMs = r.durationMs,
                        error = r.error,
                    ),
                )
                return@forEach
            }
            attempts.forEach { attempt ->
                fetchCalls.record(
                    AvailabilityFetchCallRepo.NewCall(
                        runId = runId,
                        provider = attempt.provider.name.lowercase(),
                        parentRef = parentRefKey(attempt.parentRef),
                        campsiteCount = r.campsites.size,
                        windowStart = r.window!!.startDate,
                        windowEnd = r.window.endDate,
                        outcome = attempt.outcome.name.lowercase(),
                        durationMs = attempt.durationMs,
                        error = attempt.error,
                    ),
                )
            }
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
