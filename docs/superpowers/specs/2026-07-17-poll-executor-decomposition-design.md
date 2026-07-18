# Poll Executor Decomposition

Extract service-layer abstractions from `AvailabilityPollExecutor.handle()` so the
executor orchestrates services rather than reaching directly into 5 repos.

## Problem

`AvailabilityPollExecutor.handle()` is ~170 lines that directly calls
`AvailabilityPollerRepo`, `CampsiteRepo`, `AvailabilityRepo`,
`AvailabilityRunRepo`, and `AvailabilityFetchCallRepo`. The architecture
requires scheduler code to talk to services, not repos. The executor also
inlines observation mapping, fetch-call recording, and backoff computation
that belong behind a service boundary.

## Design

### 1. Extend `AvailabilityTargetResolver` with `resolve(poller: Poller)`

Add a second overload to the existing interface:

```kotlin
internal interface AvailabilityTargetResolver {
    fun resolve(campsite: CampsiteAvailabilityTarget): ResolvedAvailabilityTarget?
    fun resolve(poller: AvailabilityPollerRepo.Poller): PollerFetchPlan?
}
```

`PollerFetchPlan` is the complete answer to "what does this poller fetch?":

```kotlin
internal data class PollerFetchPlan(
    val targets: List<ResolvedAvailabilityTarget>,
    val windowFor: (PoiDateContext, AvailabilityProviderCapabilities) -> AvailabilityWindows?,
    val cadenceSec: Int,
    val liveWatches: List<AvailabilityWatchRepo.Watch>,
)
```

Returns `null` when the poller has no live watches (the idle/skip case).

The `DbAvailabilityTargetResolver` implementation absorbs:
- `pollers.liveWatchesForPoller(poller.id)` — live-watch check
- `pollers.cadenceOverrideForPoller(poller.id)` — cadence resolution
- `resolveCadenceSec(liveWatches, poiCadenceOverrideSec)` — three-level fall-through
- `campsitesRepo.findAvailabilityTargetsByPoi(poller.poiId)` — full catalog load
- resolve + filter by provider/parentRef + distinctBy
- `dateResolver.resolvePollingWindow(...)` — window lambda construction

New dependency on `AvailabilityPollerRepo` added to `DbAvailabilityTargetResolver`.

### 2. New `AvailabilityRunService`

File: `service/availability/AvailabilityRunService.kt`

Owns the full lifecycle of one poller run: start, persist results, terminate.

```kotlin
internal class AvailabilityRunService(
    private val runs: AvailabilityRunRepo,
    private val availability: AvailabilityRepo,
    private val fetchCalls: AvailabilityFetchCallRepo,
    private val clock: Clock,
) {
    data class RunHandle(val runId: Long, val startedAt: OffsetDateTime)

    sealed class RunOutcome {
        data class Completed(val transitions: List<CellTransition>) : RunOutcome()
        data class Failed(val error: String) : RunOutcome()
    }

    fun start(pollerId: Long): RunHandle

    fun recordResults(
        handle: RunHandle,
        results: List<GroupFetchResult>,
        attemptsByGroup: Map<Pair<AvailabilityProviderId, String>, List<FailoverAvailabilityFetcher.AttemptRecord>>,
    ): RunOutcome

    fun failWithError(handle: RunHandle, error: String)

    fun computeNextRunAt(pollerId: Long, cadenceSec: Int, failed: Boolean): OffsetDateTime
}
```

`recordResults` encapsulates:
- Observation mapping (the current `writeCube` logic — maps `GroupFetchResult` → `AvailabilityRepo.Observation` → `recordObservations`)
- `availability.markElapsedAsPast`
- Fetch-call recording (the current `recordFetchCalls` logic)
- Run completion/failure (`runs.complete` / `runs.fail`)
- Duration computation

`computeNextRunAt` encapsulates:
- `runs.countConsecutiveFailures(pollerId)`
- Exponential backoff formula with ceiling
- Normal cadence on success

### 3. Resulting executor shape

```kotlin
internal class AvailabilityPollExecutor(
    private val targetResolver: AvailabilityTargetResolver,
    private val batcher: CatalogAvailabilityBatcher,
    private val runService: AvailabilityRunService,
    private val limiter: VendorRateLimiter,
    private val alertDispatcher: WatchAlertDispatcher,
    private val failoverFetcher: FailoverAvailabilityFetcher,
) {
    suspend fun handle(poller: AvailabilityPollerRepo.Poller): HandlerResult {
        val plan = targetResolver.resolve(poller)
            ?: return HandlerResult(nextRunAt = OffsetDateTime.now().plusSeconds(IDLE_RESCHEDULE_SEC))

        val staleTargets = filterStale(plan)
        if (plan has groups but none stale) return reschedule(plan.cadenceSec)
        if (!limiter.tryAcquire(...)) return reschedule(GOVERNOR_STARVED_RETRY_SEC)

        val handle = runService.start(poller.id)
        try {
            val (results, attempts) = fetchAll(staleTargets, plan.windowFor)
            val outcome = runService.recordResults(handle, results, attempts)
            if (outcome is RunOutcome.Completed) {
                runCatching { alertDispatcher.dispatch(plan.liveWatches, outcome.transitions) }
            }
        } catch (e: Exception) {
            runService.failWithError(handle, e.message ?: "unknown")
        }
        val failed = ... // derived from outcome
        return HandlerResult(nextRunAt = runService.computeNextRunAt(poller.id, plan.cadenceSec, failed))
    }
}
```

Constructor drops from 11 dependencies to 6. The executor no longer imports
any `*Repo` class.

### 4. What stays in the executor

- **Freshness predicate** — `AvailabilityRunService` exposes
  `hasFreshCoverage(campsiteIds, startDate, endDate, freshnessWindow)` as a
  pass-through to the repo. The executor passes this as the lambda to
  `batcher.filterFetchTargets`.

- **`fetchAll` private helper** — wires `batcher.fetchByGroup` with `failoverFetcher`.
  Stays as a private method since it's pure delegation/wiring, not reusable.

- **`resolveCadenceSec` top-level function** — moves into `DbAvailabilityTargetResolver`
  as an implementation detail. The executor no longer calls it directly.

### 5. Files changed

| File | Change |
|------|--------|
| `service/availability/AvailabilityTargetResolver.kt` | Add `resolve(poller)` overload |
| `service/availability/DbAvailabilityTargetResolver.kt` | Implement `resolve(poller)`, add `AvailabilityPollerRepo` dep |
| `service/availability/AvailabilityRunService.kt` | **New** — run lifecycle service |
| `service/availability/PollerFetchPlan.kt` | **New** — data class for the plan |
| `service/scheduler/jobs/AvailabilityPollExecutor.kt` | Rewrite to orchestrate services |
| Application wiring (DI / constructor sites) | Update constructor args |

### 6. Migration notes

- `resolveCadenceSec` can remain a package-level function (already `internal`) but
  moves from `scheduler/jobs/` to `service/availability/` since it's now called by the
  target resolver.
- Constants `IDLE_RESCHEDULE_SEC`, `GOVERNOR_STARVED_RETRY_SEC` stay in the executor
  (scheduler policy). `BACKOFF_BASE_MULTIPLIER`, `BACKOFF_CEILING_SEC`,
  `GLOBAL_DEFAULT_SEC` move to `AvailabilityRunService` / target resolver respectively.
- No DB schema changes. No new tables. Pure code reorganization.