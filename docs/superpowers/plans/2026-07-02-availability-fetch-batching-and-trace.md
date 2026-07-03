# Availability Fetch Batching + Trace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the availability poller from fanning out one upstream call per campsite (which rate-limited watch 1 / Upper Pines into permanent failure), make the poll→API path traceable and monitorable, and back off when rate-limited.

**Architecture:** Extract the grouping-and-fetch step that `AvailabilityServiceImpl` already performs into a shared `CatalogAvailabilityBatcher` (groups resolved targets by `(provider, parentRef, dateContext)` and issues one `catalogAvailability` call per group; timing + outcome classification live here, the actual fetch strategy is injected so the live path stays cache-backed and the poller force-fetches). Route both the live service and the poller through it. Add an `availability_fetch_call` trace table written per group call, surface it in Grafana with a rate-limit monitor, and add exponential backoff on `next_run_at` for failed runs.

**Tech Stack:** Kotlin, Ktor, jOOQ (generated bindings), Flyway migrations, Testcontainers-Postgres + JUnit5 for repo/integration tests, kotlinx-serialization.

## Global Constraints

Copied verbatim from the spec and project rules — every task inherits these:

- **SQL / jOOQ / table refs live in `repo/` classes only.** Services and the executor call repo methods; they never embed SQL. (CLAUDE.md layering)
- **Typed DTOs / data classes for request/response bodies.** No hand-built JSON strings where a data class fits.
- **No inline magic constants.** Numeric/string/duration literals at call sites → named `const val` (or config where operationally tunable). Applies to backoff base/ceiling, batch sizes, TTLs.
- **No leaky abstraction.** Vendor call-shape (months, occupancy vs map, contract codes) stays inside each adapter's `catalogAvailability`. The batcher and callers only handle `(provider, parentRef, reservables[], window)`.
- **TDD.** Failing test → run it red → minimal impl → run it green → commit. One logical change per commit.
- **The `ReservationProvider` port surface does not change.** The fix is caller-side + observability. Adapters are untouched.
- **Branch:** all work on `availability-fetch-batching-trace`. Commit messages use the `PR N:` prefix convention where a PR boundary is reached.

**Key existing shapes (do not redefine — consume as-is):**

- `ResolvedAvailabilityTarget(reservable: Reservable, provider: ReservationProvider, parentRef: ProviderRef, dateContext: PoiDateContext)` — `service/availability/ResolvedAvailabilityTarget.kt`
- `AvailabilityTargetResolver.resolve(reservable): ResolvedAvailabilityTarget?` and `requireByRid(rid): ResolvedAvailabilityTarget`
- `AvailabilityDateResolver.resolveWindow(startDate?, endDate?, context, bookingHorizonDays, maxDays, defaultDays): ResolvedDateWindow` and `resolvePollingWindow(startDate, endDate, context, bookingHorizonDays, maxDays): ResolvedDateWindow?` (returns null when no future dates)
- `ResolvedDateWindow(startDate: LocalDate, endDate: LocalDate)` — `models/availability/`
- `ReservationProvider.catalogAvailability(CatalogAvailabilityRequest(ref, reservables: List<CatalogReservableRef>, startDate, endDate, force)): AvailabilityObservationBatch`
- `AvailabilityObservationBatch.observations: List<ReservableDayObservation>` where `ReservableDayObservation(reservableId: String /* rid */, date: LocalDate, observedAt: Instant, status: AvailabilityStatus)`
- `ReservationProviderError` sealed subtypes: `RateLimited`, `UpstreamBlocked`, `UpstreamUnavailable`, `Unsupported`, `WrongRefType`
- `AvailabilitySnapshotRepo.appendObservations(SnapshotObservationBatch(runId: Long?, observations: List<SnapshotObservation(reservableId: Long, reservableRid: String?, targetDate: LocalDate, observedAt: Instant, status: AvailabilityStatus)>)): Int`
- `AvailabilityJobRunRepo.start(jobId, startedAt): Long`, `.complete(runId, snapshotCount, completedAt, durationMs): Boolean`, `.fail(runId, error, completedAt, durationMs): Boolean`
- `HandlerResult(nextRunAt: OffsetDateTime)` — the scheduler writes it via `release(...)`.

---

# PR 1 — Batcher extraction + poller realign

Ships the prod fix. No schema change. After this PR a POI watch over N sites in one campground makes ~1 upstream call per run instead of N.

## File Structure (PR 1)

- Create `service/availability/CatalogAvailabilityBatcher.kt` — grouping + window + timed fetch + outcome classification; `FetchOutcome`, `GroupFetchResult`, and the shared `Reservable.toCatalogReservableRef()` live here.
- Modify `service/availability/AvailabilityServiceImpl.kt` — route `getByRids` through the batcher; keep snapshot caching via the injected fetch lambda.
- Modify `service/scheduler/jobs/AvailabilityPollExecutor.kt` — resolve targets → batcher → batched snapshot append → complete/fail. Delete the per-site loop.
- Modify `Main.kt` — construct the batcher; stop constructing `ReservableAvailabilityFetchService`.
- Delete `service/api/ReservableAvailabilityFetchService.kt` (only the poller used it — confirmed by grep).
- Test: `service/availability/CatalogAvailabilityBatcherTest.kt`, `service/scheduler/jobs/AvailabilityPollExecutorTest.kt`.

### Task 1.1: Batcher types + shared catalog-ref extension

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CatalogAvailabilityBatcher.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityServiceImpl.kt` (remove the now-shared private `toCatalogReservableRef`)

**Interfaces:**
- Produces: `FetchOutcome` enum; `GroupFetchResult`; `internal fun Reservable.toCatalogReservableRef(): CatalogReservableRef`; `CatalogAvailabilityBatcher` (impl in Task 1.2).

- [ ] **Step 1: Write the types + extension** (no behavior yet; compile target for later tasks)

```kotlin
package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.availability.ResolvedDateWindow
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.service.reservation.CatalogReservableRef
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Platform-level outcome of one group's upstream fetch. Derived from the
 *  typed [ReservationProviderError] the adapter throws; provider-agnostic. */
enum class FetchOutcome { OK, RATE_LIMITED, UPSTREAM_5XX, BLOCKED, OTHER }

/** Result of one (provider, parentRef, dateContext) group's fetch.
 *  [window] is null when the group had no future dates and was skipped
 *  (no upstream call, no error). [batch] is non-null iff outcome == OK. */
data class GroupFetchResult(
    val provider: ReservationProvider,
    val parentRef: ProviderRef,
    val dateContext: PoiDateContext,
    val reservables: List<Reservable>,
    val window: ResolvedDateWindow?,
    val batch: AvailabilityObservationBatch?,
    val outcome: FetchOutcome,
    val durationMs: Int,
    val error: String?,
)

internal fun Reservable.toCatalogReservableRef(): CatalogReservableRef =
    CatalogReservableRef(
        rid = rid.encode(),
        vendorId = rid.vendorId,
        mapId = aspiraProviderRefLong("mapId"),
        resourceLocationId = aspiraProviderRefLong("resourceLocationId"),
    )

private fun Reservable.aspiraProviderRefLong(key: String): Long? =
    (providerRef as? JsonObject)?.get(key)?.jsonPrimitive?.longOrNull

internal fun ReservationProviderError.toFetchOutcome(): FetchOutcome =
    when (this) {
        is ReservationProviderError.RateLimited -> FetchOutcome.RATE_LIMITED
        is ReservationProviderError.UpstreamBlocked -> FetchOutcome.BLOCKED
        is ReservationProviderError.UpstreamUnavailable -> FetchOutcome.UPSTREAM_5XX
        else -> FetchOutcome.OTHER
    }
```

- [ ] **Step 2: Remove the duplicate from `AvailabilityServiceImpl.kt`**

Delete the private `private fun Reservable.toCatalogReservableRef()` and `private fun Reservable.aspiraProviderRefLong(...)` at the bottom of `AvailabilityServiceImpl.kt` (lines ~155–161 and ~196–201). The file will now use the shared `internal fun` from the batcher file (same package `service.availability`, no import needed).

- [ ] **Step 3: Compile**

Run: `cd backend && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL (the CatalogAvailabilityBatcher class itself is added in Task 1.2; this step only checks the types + extraction compile).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CatalogAvailabilityBatcher.kt backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityServiceImpl.kt
git commit -m "PR 1: add batcher result types + share toCatalogReservableRef"
```

### Task 1.2: `CatalogAvailabilityBatcher.fetchByGroup`

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CatalogAvailabilityBatcher.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/CatalogAvailabilityBatcherTest.kt`

**Interfaces:**
- Produces:
```kotlin
class CatalogAvailabilityBatcher {
    suspend fun fetchByGroup(
        targets: List<ResolvedAvailabilityTarget>,
        windowFor: (PoiDateContext, ReservationProviderCapabilities) -> ResolvedDateWindow?,
        fetch: suspend (parentRef: ProviderRef, provider: ReservationProvider, reservables: List<Reservable>, window: ResolvedDateWindow) -> AvailabilityObservationBatch,
    ): List<GroupFetchResult>
}
```
- Consumes: `ResolvedAvailabilityTarget`, `ResolvedDateWindow`, `ReservationProviderError.toFetchOutcome()`.

- [ ] **Step 1: Write the failing test**

```kotlin
package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.availability.ResolvedDateWindow
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogAvailabilityBatcherTest {
    private val window = ResolvedDateWindow(LocalDate.parse("2026-07-17"), LocalDate.parse("2026-07-31"))

    @Test
    fun `groups same-campground targets into one fetch call`() = runBlocking {
        // Two targets share provider + parentRef → exactly one fetch call.
        val provider = fakeProvider()
        val ref = ProviderRef.RecGov(recgovId = "232447")
        val targets = listOf(
            resolvedTarget(reservableRid = "site:recgov:100", provider = provider, parentRef = ref),
            resolvedTarget(reservableRid = "site:recgov:101", provider = provider, parentRef = ref),
        )
        var calls = 0
        val results = CatalogAvailabilityBatcher().fetchByGroup(
            targets = targets,
            windowFor = { _, _ -> window },
            fetch = { _, _, reservables, w ->
                calls++
                assertEquals(2, reservables.size)
                emptyBatch(w)
            },
        )
        assertEquals(1, calls)
        assertEquals(1, results.size)
        assertEquals(FetchOutcome.OK, results[0].outcome)
        assertEquals(2, results[0].reservables.size)
    }

    @Test
    fun `distinct campgrounds produce distinct calls`() = runBlocking {
        val provider = fakeProvider()
        val targets = listOf(
            resolvedTarget("site:recgov:1", provider, ProviderRef.RecGov("100")),
            resolvedTarget("site:recgov:2", provider, ProviderRef.RecGov("200")),
        )
        var calls = 0
        CatalogAvailabilityBatcher().fetchByGroup(targets, { _, _ -> window }, { _, _, _, w -> calls++; emptyBatch(w) })
        assertEquals(2, calls)
    }

    @Test
    fun `rate limited fetch is classified, not thrown`() = runBlocking {
        val provider = fakeProvider()
        val targets = listOf(resolvedTarget("site:recgov:1", provider, ProviderRef.RecGov("100")))
        val results = CatalogAvailabilityBatcher().fetchByGroup(
            targets, { _, _ -> window },
            { _, _, _, _ -> throw ReservationProviderError.RateLimited(RuntimeException("429")) },
        )
        assertEquals(FetchOutcome.RATE_LIMITED, results[0].outcome)
        assertNull(results[0].batch)
    }

    @Test
    fun `null window skips the group with no fetch call`() = runBlocking {
        val provider = fakeProvider()
        val targets = listOf(resolvedTarget("site:recgov:1", provider, ProviderRef.RecGov("100")))
        var calls = 0
        val results = CatalogAvailabilityBatcher().fetchByGroup(
            targets, { _, _ -> null }, { _, _, _, w -> calls++; emptyBatch(w) },
        )
        assertEquals(0, calls)
        assertNull(results[0].window)
        assertEquals(FetchOutcome.OK, results[0].outcome)
    }

    // --- fixtures: see helpers below (build minimal Reservable, ResolvedAvailabilityTarget,
    // fake ReservationProvider whose capabilities are recgov-shaped). emptyBatch(w) returns
    // AvailabilityObservationBatch(provider="recgov", startDate=w.startDate, endDate=w.endDate,
    // observations=emptyList()). ---
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'ca.floo.roadtrip.service.availability.CatalogAvailabilityBatcherTest'`
Expected: FAIL — `fetchByGroup` unresolved.

- [ ] **Step 3: Implement `fetchByGroup`**

Append to `CatalogAvailabilityBatcher.kt`:

```kotlin
class CatalogAvailabilityBatcher {
    private data class GroupKey(
        val provider: ReservationProvider,
        val parentRef: ProviderRef,
        val dateContext: PoiDateContext,
    )

    suspend fun fetchByGroup(
        targets: List<ResolvedAvailabilityTarget>,
        windowFor: (PoiDateContext, ReservationProviderCapabilities) -> ResolvedDateWindow?,
        fetch: suspend (parentRef: ProviderRef, provider: ReservationProvider, reservables: List<Reservable>, window: ResolvedDateWindow) -> AvailabilityObservationBatch,
    ): List<GroupFetchResult> =
        targets
            .groupBy { GroupKey(it.provider, it.parentRef, it.dateContext) }
            .map { (key, groupTargets) ->
                val reservables = groupTargets.map { it.reservable }
                val window = windowFor(key.dateContext, key.provider.capabilities)
                if (window == null) {
                    return@map GroupFetchResult(
                        provider = key.provider, parentRef = key.parentRef, dateContext = key.dateContext,
                        reservables = reservables, window = null, batch = null,
                        outcome = FetchOutcome.OK, durationMs = 0, error = null,
                    )
                }
                val startedNanos = System.nanoTime()
                try {
                    val batch = fetch(key.parentRef, key.provider, reservables, window)
                    GroupFetchResult(
                        provider = key.provider, parentRef = key.parentRef, dateContext = key.dateContext,
                        reservables = reservables, window = window, batch = batch,
                        outcome = FetchOutcome.OK, durationMs = elapsedMs(startedNanos), error = null,
                    )
                } catch (e: ReservationProviderError) {
                    GroupFetchResult(
                        provider = key.provider, parentRef = key.parentRef, dateContext = key.dateContext,
                        reservables = reservables, window = window, batch = null,
                        outcome = e.toFetchOutcome(), durationMs = elapsedMs(startedNanos),
                        error = e.message ?: e::class.simpleName,
                    )
                }
            }

    private fun elapsedMs(startedNanos: Long): Int =
        ((System.nanoTime() - startedNanos) / 1_000_000).toInt().coerceAtLeast(0)
}
```

Note: `System.nanoTime()` is allowed here (not `Date.now`); it is not a persisted timestamp, just a duration.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'ca.floo.roadtrip.service.availability.CatalogAvailabilityBatcherTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/CatalogAvailabilityBatcher.kt backend/src/test/kotlin/ca/floo/roadtrip/service/availability/CatalogAvailabilityBatcherTest.kt
git commit -m "PR 1: CatalogAvailabilityBatcher groups targets and classifies outcomes"
```

### Task 1.3: Route the live path through the batcher

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityServiceImpl.kt`

**Interfaces:**
- Consumes: `CatalogAvailabilityBatcher.fetchByGroup`, `GroupFetchResult`.
- Behavior preserved: `getByRids` still returns one `AvailabilityResponseDto` per rid, cache-backed, same errors.

- [ ] **Step 1: Refactor `getByRids` to build targets then delegate to the batcher**

Replace the `.groupBy { AvailabilityFetchGroup(...) }.forEach { ... }` block with a single batcher call. The window closure calls the existing `dateResolver.resolveWindow(...)`; the fetch closure preserves the existing `snapshotAvailability.loadOrFetch { provider.catalogAvailability(...) }` cache wrapper. Map each returned `GroupFetchResult.batch` back to per-rid DTOs exactly as `fetchCatalogReservablesAvailability` does today (filter observations by rid, copy metadata). Delete the now-unused `AvailabilityFetchGroup` data class, `fetchCatalogReservablesAvailability`, and `fetchCatalogAvailabilityBatch` private methods, folding their bodies into the two closures.

```kotlin
val batcher = CatalogAvailabilityBatcher()
val results = batcher.fetchByGroup(
    targets = resolved,
    windowFor = { context, caps ->
        dateResolver.resolveWindow(
            startDate = startDate, endDate = endDate, context = context,
            bookingHorizonDays = caps.bookingHorizonDays,
            maxDays = MAX_AVAILABILITY_DAYS, defaultDays = DEFAULT_AVAILABILITY_DAYS,
        )
    },
    fetch = { parentRef, provider, rows, window ->
        snapshotAvailability.loadOrFetch(
            SnapshotBackedAvailabilityService.Request(
                metadata = availabilityMetadata(provider.id, parentRef),
                targets = rows.map { it.toAvailabilityTarget() },
                startDate = window.startDate, endDate = window.endDate,
                ttl = snapshotFreshnessTtl(provider.id), force = force,
            ),
        ) {
            provider.catalogAvailability(
                CatalogAvailabilityRequest(
                    ref = parentRef, reservables = rows.map { it.toCatalogReservableRef() },
                    startDate = window.startDate, endDate = window.endDate, force = force,
                ),
            )
        }
    },
)
results.forEach { result ->
    val batch = result.batch ?: return@forEach
    result.reservables.forEach { reservable ->
        val rid = reservable.rid.encode()
        val ref = reservable.providerRefForReservable(result.parentRef)
        val metadata = availabilityMetadata(result.provider.id, ref, reservableId = rid)
        byRid[rid] = availabilityResponseFromObservations(
            batch.copy(
                observations = batch.observations.filter { it.reservableId == rid },
                campgroundId = metadata.campgroundId ?: batch.campgroundId,
                host = batch.host,
                mapId = metadata.mapId ?: batch.mapId,
                reservableId = rid,
            ),
        )
    }
}
```

Note: `resolved` is the existing `rids.map { targets.requireByRid(it) }`. `requireByRid` still throws `NotFound`/`UnknownCampground` before batching, preserving current error behavior. The window closure still throws `BadDateWindow` from inside `resolveWindow` — that now propagates out of `fetchByGroup` (it is not a `ReservationProviderError`, so the batcher does not catch it), matching today's behavior where `resolveWindow` throws before the fetch.

- [ ] **Step 2: Run the existing service + route tests**

Run: `cd backend && ./gradlew test --tests 'ca.floo.roadtrip.service.availability.*' --tests 'ca.floo.roadtrip.routes.Availability*'`
Expected: PASS — no behavior change. If a `BadDateWindow` test now fails because the throw site moved inside the closure, confirm the exception still surfaces from `getByRids` (it must); fix only if the batcher is wrongly swallowing it.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityServiceImpl.kt
git commit -m "PR 1: route live availability path through CatalogAvailabilityBatcher"
```

### Task 1.4: Rewrite the poller onto the batcher

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutorTest.kt`

**Interfaces:**
- Consumes: `CatalogAvailabilityBatcher`, `AvailabilityTargetResolver.resolve`, `WatchScopeResolver.resolve`, `AvailabilitySnapshotRepo.appendObservations`, `AvailabilityDateResolver.resolvePollingWindow`.
- Constructor changes from taking `ReservableAvailabilityFetchService` to taking `CatalogAvailabilityBatcher` + `AvailabilitySnapshotRepo` + `AvailabilityTargetResolver` (already present) + `AvailabilityDateResolver` (already present).
- Produces (used by Main.kt Task 1.5): new constructor signature (see impl).

- [ ] **Step 1: Write the failing test** — a POI intent over 3 sites in one campground resolves to ONE batcher call, appends that batch's snapshots with the runId, and completes.

```kotlin
package ca.floo.roadtrip.service.scheduler.jobs

// Uses fakes: a ReservationProvider that counts catalogAvailability calls and
// returns a batch with one observation per requested reservable/day; an
// in-memory AvailabilitySnapshotRepo capturing appended batches; a
// AvailabilityTargetResolver stub resolving all 3 sites to the same
// (provider, ProviderRef.RecGov("232447")) target.

class AvailabilityPollExecutorTest {
    @Test
    fun `poi over N same-campground sites makes one upstream call`() = runBlocking {
        val provider = countingRecgovProvider()   // increments provider.calls per catalogAvailability
        val (executor, captured) = executorFor(provider, sites = listOf("100", "101", "102"), campgroundId = "232447")
        val result = executor.handle(poiJob(startDate = "2026-07-17", endDate = "2026-07-31"))
        assertEquals(1, provider.calls)                       // <-- the fix: 1, not 3
        assertEquals(1, captured.appendedBatches.size)        // one batched append
        assertTrue(captured.appendedBatches.single().runId != null)
        // run recorded completed with snapshot_count == observations appended
        assertEquals("completed", captured.runStatus)
    }

    @Test
    fun `rate limited group fails the run with the outcome string`() = runBlocking {
        val provider = rateLimitedProvider()
        val (executor, captured) = executorFor(provider, sites = listOf("100"), campgroundId = "232447")
        executor.handle(poiJob("2026-07-17", "2026-07-31"))
        assertEquals("failed", captured.runStatus)
        assertEquals("rate_limited", captured.runError)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests 'ca.floo.roadtrip.service.scheduler.jobs.AvailabilityPollExecutorTest'`
Expected: FAIL — new constructor/behavior not present.

- [ ] **Step 3: Rewrite `AvailabilityPollExecutor`**

New body (replaces the per-site `runReservable`/`runPoi` loop). It resolves the intent to a `List<ResolvedAvailabilityTarget>`, batches, appends all observations mapped rid→db-id tagged with `runId`, sums the count, and fails the run if any group did not return `OK`.

```kotlin
internal class AvailabilityPollExecutor(
    private val reservablesRepo: ReservableRepo,
    private val batcher: CatalogAvailabilityBatcher,
    private val snapshots: AvailabilitySnapshotRepo,
    private val runs: AvailabilityJobRunRepo,
    private val dateResolver: AvailabilityDateResolver,
    private val targets: AvailabilityTargetResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scopeResolver = WatchScopeResolver(reservablesRepo)

    suspend fun handle(job: AvailabilityJobRepo.Job): HandlerResult {
        val startedAt = OffsetDateTime.now()
        val runId = runs.start(job.id, startedAt)
        try {
            val intent = AvailabilityJobIntent.fromJsonObject(job.intentPayload)
            val resolved = resolveTargets(intent)
            val results = batcher.fetchByGroup(
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
            runs.fail(runId, error = e.message ?: e::class.simpleName ?: "unknown", completedAt = completedAt, durationMs = durationMs(startedAt, completedAt))
        }
        return HandlerResult(nextRunAt = OffsetDateTime.now().plusSeconds(job.cadenceSec.toLong()))
    }

    /** Resolve an intent to the reservables we will poll, each carrying its
     *  provider target. POI-scope fans out to child reservables here (in the
     *  poller), but the fan-out becomes ONE grouped upstream call in the batcher. */
    private fun resolveTargets(intent: AvailabilityJobIntent): List<ResolvedAvailabilityTarget> =
        when (intent) {
            is AvailabilityJobIntent.Reservable ->
                reservablesRepo.findById(intent.reservableId)?.let { targets.resolve(it) }?.let(::listOf).orEmpty()
            is AvailabilityJobIntent.Poi ->
                scopeResolver.resolve(intent).mapNotNull { targets.resolve(it) }
        }

    /** Append every observation the group returned as a snapshot row tagged with
     *  runId, mapping each observation's rid back to its catalog db id. */
    private fun appendSnapshots(result: GroupFetchResult, runId: Long): Int {
        val batch = result.batch ?: return 0
        val idByRid = result.reservables.associateBy({ it.rid.encode() }, { it.id })
        val observations = batch.observations.mapNotNull { obs ->
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

    private fun durationMs(start: OffsetDateTime, end: OffsetDateTime): Int =
        Duration.between(start, end).toMillis().toInt().coerceAtLeast(0)
}

private const val MAX_POLL_WINDOW_DAYS = 60
```

- [ ] **Step 4: Run the executor test**

Run: `cd backend && ./gradlew test --tests 'ca.floo.roadtrip.service.scheduler.jobs.AvailabilityPollExecutorTest'`
Expected: PASS (2 tests) — `provider.calls == 1`, run completed / rate-limited path recorded.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt backend/src/test/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutorTest.kt
git commit -m "PR 1: poller resolves targets and fetches per-campground, not per-site"
```

### Task 1.5: Wire Main, delete dead fetch service, full build

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/service/api/ReservableAvailabilityFetchService.kt`

- [ ] **Step 1: Update Main wiring** — construct `CatalogAvailabilityBatcher()` and pass it (plus the existing `AvailabilitySnapshotRepo`) into `AvailabilityPollExecutor`; remove the `ReservableAvailabilityFetchService(...)` construction and its import. (Locate the executor construction near the `Scheduler<AvailabilityJob>` wiring.)

- [ ] **Step 2: Delete the dead service** (grep confirmed only the poller used it)

```bash
git rm backend/src/main/kotlin/ca/floo/roadtrip/service/api/ReservableAvailabilityFetchService.kt
```

- [ ] **Step 3: Full build + test**

Run: `cd backend && ./gradlew build`
Expected: BUILD SUCCESSFUL, all tests green. If `ReservableAvailabilityFetchService` is referenced by any test, delete that test (its behavior is now covered by `AvailabilityPollExecutorTest`).

- [ ] **Step 4: Commit (PR 1 complete)**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/Main.kt
git commit -m "PR 1: wire batcher into poller, remove per-site fetch service"
```

---

# PR 2 — Fetch-call trace + monitoring

Adds the durable trace and its Grafana surface. Depends on PR 1 (`GroupFetchResult`).

## File Structure (PR 2)

- Create `backend/src/main/resources/db/migration/V26__availability_fetch_calls.sql`.
- Create `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityFetchCallRepo.kt`.
- Modify `AvailabilityPollExecutor.kt` — write one fetch-call row per group that made a call; set `run_id` in MDC.
- Modify `grafana/dashboards/reservable-availability-watch-drill-down.json` — add "Fetch calls for this run" panel + rate-limit monitor panel.
- Modify `backend/build.gradle.kts` — add `kotlinx-coroutines-slf4j` for MDC propagation across coroutine dispatch.
- Modify `docs/reservation-providers.md` — "How a watch becomes API calls" section.
- Test `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityFetchCallRepoTest.kt`; extend `AvailabilityPollExecutorTest`.

### Task 2.1: `availability_fetch_call` migration + jOOQ codegen

**Files:**
- Create: `backend/src/main/resources/db/migration/V26__availability_fetch_calls.sql`

- [ ] **Step 1: Write the migration**

```sql
-- availability_fetch_call — one row per upstream availability fetch a poll run
-- issued, at the (provider, campground/map) group granularity produced by
-- CatalogAvailabilityBatcher. This is the trace layer between a run
-- ("failed: rate_limited") and the raw upstream calls: it shows how one watch
-- became N API calls and how each fared. Written only when a real upstream
-- call was made (skipped/no-future-date groups produce no row).
--
-- Retention: indefinite, same as availability_job_run. Hot query is
-- "all fetch calls for this run_id" (dashboard drill) and
-- "rate_limited count by provider,parent_ref over last 1h" (monitor).

CREATE TABLE availability_fetch_call (
  id                BIGSERIAL   PRIMARY KEY,
  run_id            BIGINT      NOT NULL REFERENCES availability_job_run(id) ON DELETE CASCADE,
  provider          TEXT        NOT NULL,
  parent_ref        TEXT        NOT NULL,
  reservable_count  INT         NOT NULL DEFAULT 0 CHECK (reservable_count >= 0),
  window_start      DATE        NOT NULL,
  window_end        DATE        NOT NULL,
  outcome           TEXT        NOT NULL
                                  CHECK (outcome IN ('ok','rate_limited','upstream_5xx','blocked','other')),
  duration_ms       INT         CHECK (duration_ms IS NULL OR duration_ms >= 0),
  error             TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX availability_fetch_call_run_idx
  ON availability_fetch_call (run_id);

-- Monitor path: rate-limited calls by provider/target over a recent window.
CREATE INDEX availability_fetch_call_outcome_created_idx
  ON availability_fetch_call (outcome, created_at DESC);
```

- [ ] **Step 2: Regenerate jOOQ bindings**

Run: `cd backend && ./gradlew generateJooq` (or the project's codegen task — check `build.gradle.kts` for the jOOQ task name; `flywayMigrate` may run against the codegen container first).
Expected: `AvailabilityFetchCall` table object appears under `ca/floo/roadtrip/db/generated/tables/`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V26__availability_fetch_calls.sql backend/src/main/kotlin/ca/floo/roadtrip/db/generated
git commit -m "PR 2: availability_fetch_call migration + jooq bindings"
```

### Task 2.2: `AvailabilityFetchCallRepo`

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityFetchCallRepo.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityFetchCallRepoTest.kt`

**Interfaces:**
- Produces:
```kotlin
class AvailabilityFetchCallRepo(ctx: DSLContext) {
    data class NewCall(
        val runId: Long, val provider: String, val parentRef: String,
        val reservableCount: Int, val windowStart: LocalDate, val windowEnd: LocalDate,
        val outcome: String, val durationMs: Int?, val error: String?,
    )
    fun record(call: NewCall): Long
    fun listForRun(runId: Long): List<NewCall>   // for the repo test assertion
}
```

- [ ] **Step 1: Write the failing repo test** — model on `AvailabilityJobRunRepoTest` (Testcontainers, `migrate(ds)`, `seedPoi`/`seedJob`, `AvailabilityJobRunRepo.start` to get a `run_id`). Assert `record(...)` inserts a row readable by `listForRun`, and that a bad `outcome` value violates the CHECK constraint.

```kotlin
@Test
fun `record inserts a fetch call row tied to the run`() {
    val jobId = seedJob(seedPoi())
    val runId = AvailabilityJobRunRepo(ctx).start(jobId, now())
    val repo = AvailabilityFetchCallRepo(ctx)
    repo.record(AvailabilityFetchCallRepo.NewCall(
        runId = runId, provider = "recgov", parentRef = "232447",
        reservableCount = 235, windowStart = LocalDate.parse("2026-07-17"),
        windowEnd = LocalDate.parse("2026-07-31"), outcome = "rate_limited",
        durationMs = 240941, error = "rec.gov 429 after 3 retries",
    ))
    val rows = repo.listForRun(runId)
    assertEquals(1, rows.size)
    assertEquals("rate_limited", rows[0].outcome)
    assertEquals(235, rows[0].reservableCount)
}
```

- [ ] **Step 2: Run — FAIL** (`./gradlew test --tests '*AvailabilityFetchCallRepoTest'`), unresolved repo.
- [ ] **Step 3: Implement the repo** using jOOQ DSL against `AVAILABILITY_FETCH_CALL` (insert with `returningResult(ID)`, select for `listForRun`). SQL lives only here per layering rule.
- [ ] **Step 4: Run — PASS.**
- [ ] **Step 5: Commit** — `git commit -m "PR 2: AvailabilityFetchCallRepo"`.

### Task 2.3: Executor writes trace rows

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt`
- Test: extend `AvailabilityPollExecutorTest`

**Interfaces:**
- Consumes: `AvailabilityFetchCallRepo` (new constructor param).

- [ ] **Step 1: Write the failing test** — after `handle` on a POI over one rate-limited campground, exactly one `availability_fetch_call` row exists for the run with `outcome='rate_limited'`, `reservable_count` = sites, `parent_ref` = campground id. Groups with a null window (skipped) write NO row.

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement** — inject `AvailabilityFetchCallRepo`; after `batcher.fetchByGroup(...)`, for each result **with a non-null `window`** (a real call), write a row:

```kotlin
results.filter { it.window != null }.forEach { r ->
    fetchCalls.record(
        AvailabilityFetchCallRepo.NewCall(
            runId = runId,
            provider = r.provider.id.name.lowercase(),
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
```

`parentRefKey(ProviderRef)` renders the vendor call-unit id as text: `RecGov.recgovId`, `Aspira.mapId`, `ReserveAmerica.parkId`, `ReserveCalifornia.facilityIds.joinToString(",")`. Put this as a small `private fun` in the executor (it is observability formatting, not provider dispatch — no capability leak).

- [ ] **Step 4: Run — PASS.**
- [ ] **Step 5: Commit** — `git commit -m "PR 2: poller writes availability_fetch_call trace rows"`.

### Task 2.4: `run_id` in logging MDC

**Files:**
- Modify: `backend/build.gradle.kts` (add `org.jetbrains.kotlinx:kotlinx-coroutines-slf4j`)
- Modify: `AvailabilityPollExecutor.kt`

- [ ] **Step 1: Add the dependency** and refresh.

Run: `cd backend && ./gradlew dependencies --configuration runtimeClasspath | grep coroutines-slf4j`
Expected: the artifact resolves.

- [ ] **Step 2: Wrap the run body in `MDCContext`** so the client's `Poller: GET …` / `429 …` lines carry `run_id` across coroutine dispatch:

```kotlin
withContext(MDCContext(mapOf("run_id" to runId.toString()))) {
    // resolveTargets + batcher.fetchByGroup + append + trace writes
}
```

(Import `kotlinx.coroutines.slf4j.MDCContext`. Wrap only the fetch section, inside the existing try.)

- [ ] **Step 3: Verify** with an existing/adjusted executor test that runs the handler; assert no regression (MDC is additive). Manual check deferred to canary.
- [ ] **Step 4: Commit** — `git commit -m "PR 2: correlate poller upstream logs with run_id via MDC"`.

### Task 2.5: Grafana panel + rate-limit monitor

**Files:**
- Modify: `grafana/dashboards/reservable-availability-watch-drill-down.json`

- [ ] **Step 1: Add a "Fetch calls for this run" table panel** using the `roadtrip-postgres` datasource and the existing `run_id`/`job_id`/`watch_id` drill vars + `IN ('', '__all')` guard pattern already used by the other panels in this file:

```sql
SELECT fc.created_at, fc.provider, fc.parent_ref, fc.reservable_count,
       fc.window_start, fc.window_end, fc.outcome, fc.duration_ms, fc.error
FROM availability_fetch_call fc
JOIN availability_job_run jr ON jr.id = fc.run_id
JOIN availability_job j ON j.id = jr.job_id
WHERE (${watch_id:sqlstring} IN ('', '__all') OR j.watch_id::text IN (${watch_id:sqlstring}))
  AND (${job_id:sqlstring}   IN ('', '__all') OR jr.job_id::text IN (${job_id:sqlstring}))
  AND (${run_id:sqlstring}   IN ('', '__all') OR fc.run_id::text IN (${run_id:sqlstring}))
ORDER BY fc.created_at DESC;
```

- [ ] **Step 2: Add a "Rate-limited fetches (1h)" panel** (stat or timeseries) as the monitor query:

```sql
SELECT $__timeGroup(created_at, '$__interval') AS time,
       provider || ':' || parent_ref AS metric,
       count(*)::double precision AS value
FROM availability_fetch_call
WHERE $__timeFilter(created_at) AND outcome = 'rate_limited'
GROUP BY 1, 2 ORDER BY 1;
```

- [ ] **Step 3: Validate JSON** — `python3 -c "import json; json.load(open('grafana/dashboards/reservable-availability-watch-drill-down.json'))"` (exit 0). Keep the dashboard `uid` unchanged (`reservable-watch-drill`).
- [ ] **Step 4: Commit** — `git commit -m "PR 2: watch-drill fetch-call trace panel + rate-limit monitor"`.

### Task 2.6: Docs

**Files:**
- Modify: `docs/reservation-providers.md`

- [ ] **Step 1: Add a "How a watch becomes API calls" section** documenting: watch → job → run → grouped `catalogAvailability` call(s) → `availability_fetch_call` trace rows; the batcher groups by `(provider, parentRef, dateContext)`; call-shaping stays in adapters; `run_id` MDC for deep logs. One paragraph + the hierarchy diagram from the spec.
- [ ] **Step 2: Commit (PR 2 complete)** — `git commit -m "PR 2: document watch→API-call trace model"`.

---

# PR 3 — Failure backoff

Independent of PR 2. Depends on PR 1.

## File Structure (PR 3)

- Modify `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRunRepo.kt` — add `countConsecutiveFailures(jobId)`.
- Modify `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt` — compute `nextRunAt` with exponential backoff on failure.
- Test: extend `AvailabilityJobRunRepoTest`, `AvailabilityPollExecutorTest`.

### Task 3.1: `countConsecutiveFailures`

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRunRepo.kt`
- Test: extend `AvailabilityJobRunRepoTest`

**Interfaces:**
- Produces: `fun countConsecutiveFailures(jobId: Long): Int` — number of leading `failed` runs from newest; 0 if the most recent run is not failed. Derives from run rows (source of truth), so no new column and no double-write. Called after the current run's terminal status is written, so it includes the just-failed run.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `countConsecutiveFailures counts leading failed runs`() {
    val jobId = seedJob(seedPoi())
    val repo = AvailabilityJobRunRepo(ctx)
    // oldest → newest: completed, failed, failed
    repo.start(jobId, now().minusMinutes(3)).also { repo.complete(it, 1, now().minusMinutes(3), 10) }
    repo.start(jobId, now().minusMinutes(2)).also { repo.fail(it, "rate_limited", now().minusMinutes(2), 10) }
    repo.start(jobId, now().minusMinutes(1)).also { repo.fail(it, "rate_limited", now().minusMinutes(1), 10) }
    assertEquals(2, repo.countConsecutiveFailures(jobId))
}

@Test
fun `countConsecutiveFailures is zero when newest run completed`() {
    val jobId = seedJob(seedPoi())
    val repo = AvailabilityJobRunRepo(ctx)
    repo.start(jobId, now().minusMinutes(1)).also { repo.fail(it, "x", now().minusMinutes(1), 10) }
    repo.start(jobId, now()).also { repo.complete(it, 1, now(), 10) }
    assertEquals(0, repo.countConsecutiveFailures(jobId))
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement** — one query, newest-first, stop at the first non-failed. Terminal statuses only (ignore in-flight `started`):

```kotlin
fun countConsecutiveFailures(jobId: Long): Int {
    val statuses = ctx
        .select(AVAILABILITY_JOB_RUN.STATUS)
        .from(AVAILABILITY_JOB_RUN)
        .where(AVAILABILITY_JOB_RUN.JOB_ID.eq(jobId))
        .and(AVAILABILITY_JOB_RUN.STATUS.`in`("completed", "failed"))
        .orderBy(AVAILABILITY_JOB_RUN.STARTED_AT.desc(), AVAILABILITY_JOB_RUN.ID.desc())
        .limit(CONSECUTIVE_FAILURE_SCAN_LIMIT)
        .fetch(AVAILABILITY_JOB_RUN.STATUS)
    return statuses.takeWhile { it == "failed" }.count()
}
```

Add `private const val CONSECUTIVE_FAILURE_SCAN_LIMIT = 100` at file scope (named constant, not inline; caps the scan for a permanently-failing job).

- [ ] **Step 4: Run — PASS.**
- [ ] **Step 5: Commit** — `git commit -m "PR 3: AvailabilityJobRunRepo.countConsecutiveFailures"`.

### Task 3.2: Backoff on `next_run_at`

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/jobs/AvailabilityPollExecutor.kt`
- Test: extend `AvailabilityPollExecutorTest`

**Interfaces:**
- Consumes: `AvailabilityJobRunRepo.countConsecutiveFailures`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `rate limited run backs off beyond flat cadence`() = runBlocking {
    val provider = rateLimitedProvider()
    val (executor, captured) = executorFor(provider, sites = listOf("100"), campgroundId = "232447", cadenceSec = 120)
    // Simulate a prior failed run so this failure is the 2nd consecutive.
    captured.seedPriorFailedRun()
    val before = OffsetDateTime.now()
    val result = executor.handle(poiJob("2026-07-17", "2026-07-31"))
    // 2 consecutive failures → 120 * 2^2 = 480s (> flat 120s), capped at ceiling.
    val delaySec = Duration.between(before, result.nextRunAt).seconds
    assertTrue(delaySec in 400..BACKOFF_CEILING_SEC)
}

@Test
fun `successful run schedules at flat cadence`() = runBlocking {
    val provider = okProvider()
    val (executor, _) = executorFor(provider, sites = listOf("100"), campgroundId = "232447", cadenceSec = 120)
    val before = OffsetDateTime.now()
    val result = executor.handle(poiJob("2026-07-17", "2026-07-31"))
    assertEquals(120L, Duration.between(before, result.nextRunAt).seconds)
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement backoff** in `handle`. Replace the tail `return HandlerResult(nextRunAt = now + cadenceSec)` with a computed delay based on whether the run failed and how many consecutive failures precede it:

```kotlin
// file scope, named constants (no inline magic):
private const val MAX_POLL_WINDOW_DAYS = 60
private const val BACKOFF_BASE_MULTIPLIER = 2.0
private const val BACKOFF_CEILING_SEC = 3_600L   // 1h cap on a wedged watch

// in handle(): track whether we failed, then:
val nextRunAt =
    if (runFailed) {
        val failures = runs.countConsecutiveFailures(job.id)   // includes the run we just failed
        val backoff = (job.cadenceSec * Math.pow(BACKOFF_BASE_MULTIPLIER, failures.toDouble())).toLong()
        OffsetDateTime.now().plusSeconds(backoff.coerceAtMost(BACKOFF_CEILING_SEC))
    } else {
        OffsetDateTime.now().plusSeconds(job.cadenceSec.toLong())
    }
return HandlerResult(nextRunAt = nextRunAt)
```

Set `runFailed = true` in both the group-failure branch and the `catch` branch; `false` on complete. (A single `var runFailed = false` at the top of `handle`, flipped where `runs.fail(...)` is called.)

- [ ] **Step 4: Run — PASS.**

- [ ] **Step 5: Full build**

Run: `cd backend && ./gradlew build`
Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 6: Commit (PR 3 complete)** — `git commit -m "PR 3: exponential backoff on rate-limited/failed poll runs"`.

---

## Self-Review

**Spec coverage:**
- Batcher seam / dedup → PR 1 Tasks 1.1–1.3 (shared batcher, live path + poller both use it). ✓
- Per-site fan-out fix → PR 1 Task 1.4 (`provider.calls == 1` test). ✓
- Call-shaping stays in adapters → batcher only handles `(provider, parentRef, reservables, window)`; adapters untouched (Global Constraints). ✓
- `availability_fetch_call` trace (option A) → PR 2 Tasks 2.1–2.3. ✓
- `run_id` in MDC → PR 2 Task 2.4. ✓
- Grafana panel + rate-limit monitor → PR 2 Task 2.5. ✓
- Docs → PR 2 Task 2.6. ✓
- Backoff → PR 3 Tasks 3.1–3.2. ✓
- Backoff state = derived from run rows (resolves the spec's open question toward "derive," not a new column — avoids double-write with the scheduler's `release`). ✓
- Testing requirements (1-call, 2-campground, rate_limited row + backoff, live path unchanged) → covered across 1.2, 1.3, 1.4, 2.3, 3.2. ✓

**Placeholder scan:** No TBD/TODO; every code step shows code; test fixtures for the executor (fake provider/repo) are described with exact behavior — the implementer builds them from the named types. No "add error handling" hand-waves.

**Type consistency:** `GroupFetchResult`, `FetchOutcome`, `fetchByGroup`, `toCatalogReservableRef`, `NewCall`, `countConsecutiveFailures`, `parentRefKey` are used with identical signatures across the tasks that define and consume them. `outcome` string values (`ok|rate_limited|upstream_5xx|blocked|other`) match between `FetchOutcome.name.lowercase()`, the migration CHECK, and the dashboard SQL.

**Note for the implementer:** jOOQ codegen task name (Task 2.2 Step 2) and the exact `Main.kt` executor construction site (Task 1.5) must be confirmed against the repo — both are referenced but their surrounding wiring wasn't quoted here. The `kotlinx-coroutines-slf4j` version should match the existing `kotlinx-coroutines-core` version already in `build.gradle.kts`.
