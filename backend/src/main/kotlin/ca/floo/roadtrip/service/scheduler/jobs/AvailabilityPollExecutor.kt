package ca.floo.roadtrip.service.scheduler.jobs

import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.scheduler.HandlerResult
import ca.floo.roadtrip.observability.PollSkipReason
import ca.floo.roadtrip.observability.RoadtripMetrics
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.service.availability.AvailabilityRunService
import ca.floo.roadtrip.service.availability.AvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.CatalogAvailabilityBatcher
import ca.floo.roadtrip.service.availability.FailoverAvailabilityFetcher
import ca.floo.roadtrip.service.availability.ResolvedAvailabilityTarget
import ca.floo.roadtrip.service.availability.WatchAlertDispatcher
import ca.floo.roadtrip.service.availability.availabilityProviderErrorFromAttempt
import ca.floo.roadtrip.service.availability.parentRefKey
import ca.floo.roadtrip.service.ratelimit.VendorRateLimiter
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration

private const val IDLE_RESCHEDULE_SEC = 300L
private const val GOVERNOR_STARVED_RETRY_SEC = 15L

internal class AvailabilityPollExecutor(
    private val targetResolver: AvailabilityTargetResolver,
    private val batcher: CatalogAvailabilityBatcher,
    private val runService: AvailabilityRunService,
    private val limiter: VendorRateLimiter,
    private val alertDispatcher: WatchAlertDispatcher,
    private val failoverFetcher: FailoverAvailabilityFetcher,
    private val metrics: RoadtripMetrics = RoadtripMetrics.NoOp,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun handle(poller: AvailabilityPollerRepo.Poller): HandlerResult {
        val plan =
            targetResolver.resolve(poller)
                ?: return HandlerResult(nextRunAt = runService.nowUtc().plusSeconds(IDLE_RESCHEDULE_SEC))

        val freshnessWindow = Duration.ofSeconds(plan.cadenceSec.toLong())
        val staleTargets =
            batcher.filterFetchTargets(plan.targets, plan.windowFor) { rows, windows ->
                !runService.hasFreshCoverage(
                    campsiteIds = rows.map { it.campsite.id },
                    startDate = windows.fetch.startDate,
                    endDate = windows.fetch.endDate,
                    freshAtOrAfter = runService.nowUtc().minus(freshnessWindow),
                )
            }

        val bucketCount = batcher.countFetchGroups(plan.targets, plan.windowFor)
        val staleBucketCount = batcher.countFetchGroups(staleTargets, plan.windowFor)
        if (bucketCount > 0 && staleBucketCount == 0) {
            log.info("poller {} skipped fetch: {} group(s) fresh within {}s", poller.id, bucketCount, freshnessWindow.seconds)
            recordSkip(poller, PollSkipReason.COVERAGE_FRESH)
            return HandlerResult(nextRunAt = runService.nowUtc().plusSeconds(plan.cadenceSec.toLong()))
        }
        if (staleBucketCount > 0 && !limiter.tryAcquire(poller.provider, staleBucketCount.toLong())) {
            log.info(
                "poller {} governor starved ({} tokens for {}); rescheduling in {}s",
                poller.id,
                staleBucketCount,
                poller.provider,
                GOVERNOR_STARVED_RETRY_SEC,
            )
            recordSkip(poller, PollSkipReason.GOVERNOR_STARVED)
            return HandlerResult(nextRunAt = runService.nowUtc().plusSeconds(GOVERNOR_STARVED_RETRY_SEC))
        }

        val handle = runService.start(poller.id)
        var runFailed = false
        val attemptsByGroup =
            mutableMapOf<Pair<BookingProvider, String>, List<FailoverAvailabilityFetcher.AttemptRecord>>()
        try {
            withContext(MDCContext(mapOf("run_id" to handle.runId.toString()))) {
                val results =
                    batcher.fetchByGroup(
                        targets = staleTargets,
                        windowFor = plan.windowFor,
                        fetch = { campground, provider, rows, windows ->
                            val result = fetchWithFailover(rows, windows.fetch)
                            val refKey = provider.parentRefFor(campground)?.let(::parentRefKey) ?: "unknown"
                            attemptsByGroup[provider.id to refKey] = result.attempts
                            result.batch ?: throw availabilityProviderErrorFromAttempt(result.attempts.lastOrNull())
                        },
                    )
                val outcome = runService.recordResults(handle, results, attemptsByGroup)
                when (outcome) {
                    is AvailabilityRunService.RunOutcome.Completed -> {
                        runCatching { alertDispatcher.dispatch(plan.liveWatches, outcome.transitions) }
                            .onFailure { log.warn("poller {} run {} alert dispatch failed: {}", poller.id, handle.runId, it.message) }
                    }
                    is AvailabilityRunService.RunOutcome.Failed -> {
                        runFailed = true
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("poller {} run {} failed: {}", poller.id, handle.runId, e.message)
            runFailed = true
            runService.failWithError(handle, e.message ?: e::class.simpleName ?: "unknown")
        }
        return HandlerResult(nextRunAt = runService.computeNextRunAt(poller.id, plan.cadenceSec, runFailed))
    }

    /** A skipped cycle is the difference between "nothing needed fetching" and
     *  "the poller is wedged" — indistinguishable from fetch counts alone.
     *  `availability_poller.provider` is the stored id string; an unknown value
     *  would already have failed the poll itself, so telemetry declines to throw
     *  over it. */
    private fun recordSkip(
        poller: AvailabilityPollerRepo.Poller,
        reason: PollSkipReason,
    ) {
        BookingProvider.fromIdOrNull(poller.provider)?.let { metrics.availabilityPollSkipped(it, reason) }
    }

    private suspend fun fetchWithFailover(
        rows: List<ResolvedAvailabilityTarget>,
        fetchWindow: ResolvedDateWindow,
    ): FailoverAvailabilityFetcher.FailoverResult {
        val first = rows.first()
        return failoverFetcher.fetch(
            providers = first.candidates,
            campground = first.campground,
            campsites = rows.map { it.campsite },
            window = fetchWindow,
        )
    }
}
