# Poll Executor Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract service-layer abstractions from `AvailabilityPollExecutor` so it orchestrates services instead of reaching into repos directly.

**Architecture:** Add `resolve(poller)` overload to `AvailabilityTargetResolver` returning a `PollerFetchPlan`, create `AvailabilityRunService` to own run lifecycle + observation persistence, then rewrite the executor to call these services. All existing tests must pass unchanged — they validate behavior, not internal structure.

**Tech Stack:** Kotlin, jOOQ, Koin DI, JUnit 5, coroutines

## Global Constraints

- No DB schema changes. Pure code reorganization.
- All 30+ existing `AvailabilityPollExecutorTest` tests must pass green without modification (they test through the executor's public `handle()` method).
- Follow existing `internal` visibility for service-layer classes.
- No new test files needed — existing integration tests cover all paths. Unit tests for `resolveCadenceSec` already exist in the executor test and will move with it.

---

### Task 1: Create `PollerFetchPlan` data class

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/PollerFetchPlan.kt`

**Interfaces:**
- Consumes: `ResolvedAvailabilityTarget`, `AvailabilityWindows`, `PoiDateContext`, `AvailabilityProviderCapabilities`, `AvailabilityWatchRepo.Watch`
- Produces: `PollerFetchPlan` — used by the executor (Task 4) and the target resolver (Task 2)

- [ ] **Step 1: Create PollerFetchPlan.kt**

```kotlin
package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityWindows
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.repo.AvailabilityWatchRepo

internal data class PollerFetchPlan(
    val targets: List<ResolvedAvailabilityTarget>,
    val windowFor: (PoiDateContext, AvailabilityProviderCapabilities) -> AvailabilityWindows?,
    val cadenceSec: Int,
    val liveWatches: List<AvailabilityWatchRepo.Watch>,
)
```

- [ ] **Step 2: Verify compilation**

Run: `cd backend && ../gradlew compileKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/PollerFetchPlan.kt
git commit -m "refactor: add PollerFetchPlan data class"
```

---

### Task 2: Extend `AvailabilityTargetResolver` with `resolve(poller)` and implement in `DbAvailabilityTargetResolver`

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityTargetResolver.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/DbAvailabilityTargetResolver.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt` (add `AvailabilityPollerRepo` dep)

**Interfaces:**
- Consumes: `PollerFetchPlan` (Task 1), `AvailabilityPollerRepo.Poller`, `AvailabilityPollerRepo.liveWatchesForPoller`, `AvailabilityPollerRepo.cadenceOverrideForPoller`, `CampsiteRepo.findAvailabilityTargetsByPoi`, `AvailabilityDateResolver.resolvePollingWindow`, `resolveCadenceSec` (moved here)
- Produces: `AvailabilityTargetResolver.resolve(poller): PollerFetchPlan?` — called by the executor (Task 4)

- [ ] **Step 1: Add the overload to the interface**

In `AvailabilityTargetResolver.kt`, add:

```kotlin
import ca.floo.roadtrip.repo.AvailabilityPollerRepo

internal interface AvailabilityTargetResolver {
    fun resolve(campsite: CampsiteAvailabilityTarget): ResolvedAvailabilityTarget?
    fun resolve(poller: AvailabilityPollerRepo.Poller): PollerFetchPlan?
}
```

- [ ] **Step 2: Move `resolveCadenceSec` into the availability package**

Move the function from `service/scheduler/jobs/AvailabilityPollExecutor.kt` to a new file `service/availability/ResolveCadence.kt` (or inline it in `DbAvailabilityTargetResolver`). Keep it `internal` and package-level:

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/ResolveCadence.kt`:

```kotlin
package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityWatchRepo

private const val GLOBAL_DEFAULT_SEC = 300

internal fun resolveCadenceSec(
    liveWatches: List<AvailabilityWatchRepo.Watch>,
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
```

- [ ] **Step 3: Implement `resolve(poller)` in `DbAvailabilityTargetResolver`**

Add `AvailabilityPollerRepo` as a constructor parameter and implement:

```kotlin
import ca.floo.roadtrip.repo.AvailabilityPollerRepo

internal class DbAvailabilityTargetResolver(
    private val providerRefs: CampsiteProviderRepo,
    private val campsitesRepo: CampsiteRepo,
    private val availabilityProviders: AvailabilityProviderRegistry,
    private val dateResolver: AvailabilityDateResolver,
    private val pollers: AvailabilityPollerRepo,
) : AvailabilityTargetResolver {

    override fun resolve(poller: AvailabilityPollerRepo.Poller): PollerFetchPlan? {
        val liveWatches = pollers.liveWatchesForPoller(poller.id)
        if (liveWatches.isEmpty()) return null

        val poiCadenceOverrideSec = pollers.cadenceOverrideForPoller(poller.id)
        val cadenceSec = resolveCadenceSec(liveWatches, poiCadenceOverrideSec)

        val targets =
            campsitesRepo
                .findAvailabilityTargetsByPoi(poller.poiId)
                .mapNotNull { resolve(it) }
                .filter {
                    parentRefKey(it.parentRef) == poller.parentRef &&
                        it.provider.id.name.lowercase() == poller.provider
                }
                .distinctBy { it.campsite.id }

        val windowFor: (
            ca.floo.roadtrip.model.availability.PoiDateContext,
            ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities,
        ) -> ca.floo.roadtrip.model.availability.AvailabilityWindows? = { context, caps ->
            val resolvedWindow =
                dateResolver.resolvePollingWindow(
                    context = context,
                    maxPollWindowDays = caps.maxPollWindowDays,
                    bookingHorizonDays = caps.bookingHorizonDays,
                )
            resolvedWindow?.let {
                ca.floo.roadtrip.model.availability.AvailabilityWindows(target = it, fetch = it)
            }
        }

        return PollerFetchPlan(
            targets = targets,
            windowFor = windowFor,
            cadenceSec = cadenceSec,
            liveWatches = liveWatches,
        )
    }
    // ... existing resolve(campsite) unchanged
}
```

- [ ] **Step 4: Update DI wiring in ServiceModule.kt**

Change the `DbAvailabilityTargetResolver` construction at ~line 113 to include `pollers`:

```kotlin
single {
    DbAvailabilityTargetResolver(
        providerRefs = get<CampsiteProviderRepo>(),
        campsitesRepo = get<CampsiteRepo>(),
        availabilityProviders = get<AvailabilityProviderRegistry>(),
        dateResolver = get<AvailabilityDateResolver>(),
        pollers = get<AvailabilityPollerRepo>(),
    )
}
```

- [ ] **Step 5: Verify compilation**

Run: `cd backend && ../gradlew compileKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (the executor still compiles — it still has its own inline logic; we haven't removed it yet)

- [ ] **Step 6: Commit**

```
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityTargetResolver.kt
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/DbAvailabilityTargetResolver.kt
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/ResolveCadence.kt
git add backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt
git commit -m "refactor: add resolve(poller) overload to AvailabilityTargetResolver"
```

---

### Task 3: Create `AvailabilityRunService`

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityRunService.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`

**Interfaces:**
- Consumes: `AvailabilityRunRepo`, `AvailabilityRepo`, `AvailabilityFetchCallRepo`, `GroupFetchResult`, `FailoverAvailabilityFetcher.AttemptRecord`, `parentRefKey`
- Produces: `AvailabilityRunService.start(pollerId): RunHandle`, `AvailabilityRunService.recordResults(handle, results, attempts): RunOutcome`, `AvailabilityRunService.failWithError(handle, error)`, `AvailabilityRunService.computeNextRunAt(pollerId, cadenceSec, failed): OffsetDateTime`, `AvailabilityRunService.hasFreshCoverage(campsiteIds, startDate, endDate, freshAtOrAfter): Boolean` — all called by executor (Task 4)

- [ ] **Step 1: Create AvailabilityRunService.kt**

```kotlin
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
import java.time.ZoneOffset

private const val BACKOFF_BASE_MULTIPLIER = 2.0
private const val BACKOFF_CEILING_SEC = 3_600L

internal class AvailabilityRunService(
    private val runs: AvailabilityRunRepo,
    private val availability: AvailabilityRepo,
    private val fetchCalls: AvailabilityFetchCallRepo,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class RunHandle(val runId: Long, val startedAt: OffsetDateTime)

    sealed class RunOutcome {
        data class Completed(val transitions: List<CellTransition>) : RunOutcome()
        data class Failed(val error: String) : RunOutcome()
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

    fun failWithError(handle: RunHandle, error: String) {
        val completedAt = OffsetDateTime.now(clock)
        runs.fail(handle.runId, error = error, completedAt = completedAt, durationMs = durationMs(handle.startedAt, completedAt))
    }

    fun computeNextRunAt(pollerId: Long, cadenceSec: Int, failed: Boolean): OffsetDateTime =
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
                        provider = r.provider.id.name.lowercase(),
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

    private fun durationMs(start: OffsetDateTime, end: OffsetDateTime): Int =
        Duration.between(start, end).toMillis().toInt().coerceAtLeast(0)
}
```

- [ ] **Step 2: Register in ServiceModule.kt**

Add before the `AvailabilityPollExecutor` single block (~line 205):

```kotlin
single {
    AvailabilityRunService(
        runs = get<AvailabilityRunRepo>(),
        availability = get<AvailabilityRepo>(),
        fetchCalls = get<AvailabilityFetchCallRepo>(),
    )
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd backend && ../gradlew compileKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityRunService.kt
git add backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt
git commit -m "refactor: add AvailabilityRunService for run lifecycle and observation persistence"
```

---

### Task 4: Rewrite `AvailabilityPollExecutor` to orchestrate services

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutorTest.kt`

**Interfaces:**
- Consumes: `AvailabilityTargetResolver.resolve(poller)` (Task 2), `AvailabilityRunService` (Task 3), `CatalogAvailabilityBatcher`, `FailoverAvailabilityFetcher`, `VendorRateLimiter`, `WatchAlertDispatcher`
- Produces: `handle(poller): HandlerResult` — unchanged public contract

- [ ] **Step 1: Rewrite AvailabilityPollExecutor.kt**

Replace the entire file with:

```kotlin
package ca.floo.roadtrip.service.scheduler.jobs

import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.scheduler.HandlerResult
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.service.availability.AvailabilityRunService
import ca.floo.roadtrip.service.availability.AvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.CatalogAvailabilityBatcher
import ca.floo.roadtrip.service.availability.FailoverAvailabilityFetcher
import ca.floo.roadtrip.service.availability.FetchOutcome
import ca.floo.roadtrip.service.availability.GroupFetchResult
import ca.floo.roadtrip.service.availability.PollerFetchPlan
import ca.floo.roadtrip.service.availability.ProviderCandidate
import ca.floo.roadtrip.service.availability.ResolvedAvailabilityTarget
import ca.floo.roadtrip.service.availability.WatchAlertDispatcher
import ca.floo.roadtrip.service.availability.parentRefKey
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.ratelimit.VendorRateLimiter
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

private const val IDLE_RESCHEDULE_SEC = 300L
private const val GOVERNOR_STARVED_RETRY_SEC = 15L

internal class AvailabilityPollExecutor(
    private val targetResolver: AvailabilityTargetResolver,
    private val batcher: CatalogAvailabilityBatcher,
    private val runService: AvailabilityRunService,
    private val limiter: VendorRateLimiter,
    private val alertDispatcher: WatchAlertDispatcher,
    private val failoverFetcher: FailoverAvailabilityFetcher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun handle(poller: AvailabilityPollerRepo.Poller): HandlerResult {
        val plan = targetResolver.resolve(poller)
            ?: return HandlerResult(nextRunAt = OffsetDateTime.now().plusSeconds(IDLE_RESCHEDULE_SEC))

        val freshnessWindow = Duration.ofSeconds(plan.cadenceSec.toLong())
        val staleTargets = batcher.filterFetchTargets(plan.targets, plan.windowFor) { rows, windows ->
            !runService.hasFreshCoverage(
                campsiteIds = rows.map { it.campsite.id },
                startDate = windows.fetch.startDate,
                endDate = windows.fetch.endDate,
                freshAtOrAfter = OffsetDateTime.now(ZoneOffset.UTC).minus(freshnessWindow),
            )
        }

        val bucketCount = batcher.countFetchGroups(plan.targets, plan.windowFor)
        val staleBucketCount = batcher.countFetchGroups(staleTargets, plan.windowFor)
        if (bucketCount > 0 && staleBucketCount == 0) {
            log.info("poller {} skipped fetch: {} group(s) fresh within {}s", poller.id, bucketCount, freshnessWindow.seconds)
            return HandlerResult(nextRunAt = OffsetDateTime.now().plusSeconds(plan.cadenceSec.toLong()))
        }
        if (staleBucketCount > 0 && !limiter.tryAcquire(poller.provider, staleBucketCount.toLong())) {
            log.info("poller {} governor starved ({} tokens for {}); rescheduling in {}s", poller.id, staleBucketCount, poller.provider, GOVERNOR_STARVED_RETRY_SEC)
            return HandlerResult(nextRunAt = OffsetDateTime.now().plusSeconds(GOVERNOR_STARVED_RETRY_SEC))
        }

        val handle = runService.start(poller.id)
        var runFailed = false
        val attemptsByGroup =
            mutableMapOf<Pair<AvailabilityProviderId, String>, List<FailoverAvailabilityFetcher.AttemptRecord>>()
        try {
            withContext(MDCContext(mapOf("run_id" to handle.runId.toString()))) {
                val results = batcher.fetchByGroup(
                    targets = staleTargets,
                    windowFor = plan.windowFor,
                    fetch = { parentRef, provider, rows, windows ->
                        val result = fetchWithFailover(rows, windows.fetch)
                        attemptsByGroup[provider.id to parentRefKey(parentRef)] = result.attempts
                        result.batch ?: throw synthesizedError(result.attempts.lastOrNull())
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

    private suspend fun fetchWithFailover(
        rows: List<ResolvedAvailabilityTarget>,
        fetchWindow: ResolvedDateWindow,
    ): FailoverAvailabilityFetcher.FailoverResult {
        val groupCandidates = rows.first().candidates
        val preferredRefs = rows.map { it.catalogRef }
        return failoverFetcher.fetch(
            candidates = groupCandidates,
            campsites = rows.map { it.campsite },
            window = fetchWindow,
            translateRefs = { candidate ->
                if (candidate === groupCandidates.first()) {
                    preferredRefs
                } else {
                    catalogRefsFor(candidate, rows)
                }
            },
        )
    }

    private fun catalogRefsFor(
        candidate: ProviderCandidate,
        rows: List<ResolvedAvailabilityTarget>,
    ): List<CatalogCampsiteRef> {
        val refs =
            rows.mapNotNull { row ->
                row.candidates
                    .firstOrNull { it.provider.id == candidate.provider.id && it.parentRef == candidate.parentRef }
                    ?.catalogRef
            }
        return refs.takeIf { it.size == rows.size } ?: emptyList()
    }

    private fun synthesizedError(last: FailoverAvailabilityFetcher.AttemptRecord?): AvailabilityProviderError {
        val message = last?.error ?: "no availability candidates available"
        return when (last?.outcome) {
            FetchOutcome.RATE_LIMITED -> AvailabilityProviderError.RateLimited(RuntimeException(message))
            FetchOutcome.BLOCKED -> AvailabilityProviderError.UpstreamBlocked(RuntimeException(message))
            FetchOutcome.UPSTREAM_5XX,
            FetchOutcome.OK,
            FetchOutcome.OTHER,
            null,
            -> AvailabilityProviderError.UpstreamUnavailable(RuntimeException(message))
        }
    }
}
```

- [ ] **Step 2: Update DI wiring in ServiceModule.kt**

Replace the executor construction (~line 206-219) with:

```kotlin
single {
    AvailabilityPollExecutor(
        targetResolver = get<DbAvailabilityTargetResolver>(),
        batcher = CatalogAvailabilityBatcher(),
        runService = get<AvailabilityRunService>(),
        limiter = get<VendorRateLimiter>(),
        alertDispatcher = get<WatchAlertDispatcher>(),
        failoverFetcher = get<FailoverAvailabilityFetcher>(),
    )
}
```

- [ ] **Step 3: Update the test helper `executorFor` in AvailabilityPollExecutorTest.kt**

Replace the `executorFor` method (~line 372-403) and `targetsFor` helper. The test constructs the executor directly, so it needs to match the new constructor:

```kotlin
private fun executorFor(
    provider: AvailabilityProvider,
    limiter: VendorRateLimiter = RecordingLimiter(grant = true),
    alertDispatcher: WatchAlertDispatcher = disabledDispatcher(),
    failoverFetcher: FailoverAvailabilityFetcher =
        FailoverAvailabilityFetcher(cooldowns = ProviderCooldownTracker(cooldown = testProviderCooldown)),
): AvailabilityPollExecutor {
    val campsitesRepo = CampsiteRepo(ctx)
    val registry = AvailabilityProviderRegistry(mapOf("test" to provider))
    val dateResolver = AvailabilityDateResolver(clock = testClock)
    val targets =
        DbAvailabilityTargetResolver(
            providerRefs = CampsiteProviderRepo(ctx),
            campsitesRepo = campsitesRepo,
            availabilityProviders = registry,
            dateResolver = dateResolver,
            pollers = AvailabilityPollerRepo(ctx),
        )
    val runService = AvailabilityRunService(
        runs = AvailabilityRunRepo(ctx),
        availability = AvailabilityRepo(ctx),
        fetchCalls = AvailabilityFetchCallRepo(ctx),
        clock = testClock,
    )
    return AvailabilityPollExecutor(
        targetResolver = targets,
        batcher = CatalogAvailabilityBatcher(),
        runService = runService,
        limiter = limiter,
        alertDispatcher = alertDispatcher,
        failoverFetcher = failoverFetcher,
    )
}
```

Also update `targetsFor` to include the new param:

```kotlin
private fun targetsFor(provider: AvailabilityProvider): DbAvailabilityTargetResolver =
    DbAvailabilityTargetResolver(
        providerRefs = CampsiteProviderRepo(ctx),
        campsitesRepo = CampsiteRepo(ctx),
        availabilityProviders = AvailabilityProviderRegistry(mapOf("test" to provider)),
        dateResolver = AvailabilityDateResolver(),
        pollers = AvailabilityPollerRepo(ctx),
    )
```

And update `membershipFor` similarly:

```kotlin
private fun membershipFor(provider: AvailabilityProvider): AvailabilityPollerMembership {
    val campsitesRepo = CampsiteRepo(ctx)
    val registry = AvailabilityProviderRegistry(mapOf("test" to provider))
    val targets =
        DbAvailabilityTargetResolver(
            providerRefs = CampsiteProviderRepo(ctx),
            campsitesRepo = campsitesRepo,
            availabilityProviders = registry,
            dateResolver = AvailabilityDateResolver(),
            pollers = AvailabilityPollerRepo(ctx),
        )
    return AvailabilityPollerMembership(WatchScopeResolver(campsitesRepo), targets)
}
```

- [ ] **Step 4: Update the `resolveCadenceSec` test imports**

The cadence tests at the bottom of `AvailabilityPollExecutorTest.kt` call `resolveCadenceSec` directly. Update the import to point at the new location:

Remove: `import ca.floo.roadtrip.service.scheduler.jobs.resolveCadenceSec` (it was implicitly in scope from the same package)
Add: `import ca.floo.roadtrip.service.availability.resolveCadenceSec`

- [ ] **Step 5: Remove old imports and constants from executor package**

The old executor file is gone (replaced in Step 1). Make sure the old `resolveCadenceSec` and constants `GLOBAL_DEFAULT_SEC`, `BACKOFF_BASE_MULTIPLIER`, `BACKOFF_CEILING_SEC` are no longer in `service/scheduler/jobs/`. They now live in `ResolveCadence.kt` and `AvailabilityRunService.kt`.

- [ ] **Step 6: Fix any remaining compilation issues**

Run: `cd backend && ../gradlew compileKotlin 2>&1 | tail -20`

Check for:
- `CampsiteRoutes.kt` constructs `DbAvailabilityTargetResolver` directly (~line 52) — needs the new `pollers` param
- Any other call sites that construct `DbAvailabilityTargetResolver`

For `CampsiteRoutes.kt`, add the pollers dep:
```kotlin
DbAvailabilityTargetResolver(
    providerRefs = ...,
    campsitesRepo = ...,
    availabilityProviders = ...,
    dateResolver = ...,
    pollers = AvailabilityPollerRepo(ctx),
)
```

- [ ] **Step 7: Run the full test suite**

Run: `cd backend && ../gradlew test 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 8: Run ktlint**

Run: `cd backend && ../gradlew ktlintCheck 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL (no formatting violations)

- [ ] **Step 9: Commit**

```
git add -A
git commit -m "refactor: rewrite AvailabilityPollExecutor to orchestrate services"
```

---

### Task 5: Clean up dead code and verify

**Files:**
- Verify no leftover references to removed imports/constants
- Verify `DbAvailabilityTargetResolverTest` still passes (it only tests `resolve(campsite)`)

- [ ] **Step 1: Search for dead references**

Run: `grep -rn "GLOBAL_DEFAULT_SEC\|BACKOFF_BASE_MULTIPLIER\|BACKOFF_CEILING_SEC" backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/ 2>/dev/null`
Expected: no output (these constants moved to their new homes)

Run: `grep -rn "resolveCadenceSec" backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/ 2>/dev/null`
Expected: no output (moved to `service/availability/ResolveCadence.kt`)

- [ ] **Step 2: Run full test suite one final time**

Run: `cd backend && ../gradlew test 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify executor no longer imports any Repo class**

Run: `grep -n "import.*\.repo\." backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt`
Expected: only `import ca.floo.roadtrip.repo.AvailabilityPollerRepo` (for the `Poller` type in the method signature — this is acceptable since `Poller` is the scheduler's own domain type)

- [ ] **Step 4: Commit if any cleanup was needed**

```
git add -A
git commit -m "refactor: remove dead executor code after decomposition"
```
