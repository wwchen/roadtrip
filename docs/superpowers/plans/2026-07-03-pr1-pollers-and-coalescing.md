# PR1: Pollers + Coalescing (physics) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Coalesce the upstream availability fetch across watches by making the schedulable unit a **poller** keyed on `(provider, parent_ref)` — one poller == one upstream call unit — instead of one job per watch.

**Architecture:** Introduce `availability_poller` (the schedulable, absorbing `availability_job`) and a `availability_watch_poller` join. When a watch is written, membership maintenance resolves it to the `parentRef`(s) it touches and links pollers. The executor takes a poller, loads its **live watches**, derives window + cadence at run start (nothing frozen), resolves all reservables the watches touch, and issues one grouped fetch via the existing `CatalogAvailabilityBatcher`. `availability_job_run` is renamed `availability_run` (`job_id` → `poller_id`). Snapshot writing is **unchanged** (append per observation); the cube (PR3) comes later.

**Tech Stack:** Kotlin, Ktor, jOOQ (codegen from Flyway migrations), Postgres, Testcontainers (repo tests), kotlinx.coroutines, Grafana (Postgres-datasource JSON dashboards).

## Global Constraints

- **Build needs JDK 17.** `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` before any `./gradlew` from repo root. Corretto 25 (default) breaks the Kotlin compiler.
- **jOOQ includes allowlist.** Any new table must be added to `database.includes` in `backend/build.gradle.kts:175-197` (pipe-joined list) or codegen silently skips it. Renamed tables must have their old name replaced.
- **Layering (docs/backend-architecture.md):** `routes → service → repo/clients`; `repo` owns all SQL/jOOQ; `service` owns orchestration and holds no Ktor/SQL; `models` is a leaf. Routes call named repo/service methods.
- **No leaky abstractions (docs/reservation-providers.md):** the poller/membership/executor must not branch on vendor. `parentRefKey(ref)` is pure formatting of a value the batcher's grouping key already picked — the one allowed `when` over `ProviderRef`.
- **No inline magic constants.** Cadence defaults, backoff base/ceiling, window caps, tick/lease durations — all named `const val`.
- **Coalescing key is `(provider, parent_ref)`**, `parent_ref` text = `parentRefKey(providerRef)`. Never key on `poi`.
- **Watches remain single-scope in PR1** (exactly one of `poi_id`/`reservable_id`). Multi-target watches are PR2.
- **Cadence in PR1** = `min` over live watches of `watch.cadence_sec`, falling through to `GLOBAL_DEFAULT_SEC`. `pois.cadence_override_sec` and the vendor governor are PR4.

---

## File Structure

**New files:**
- `backend/src/main/resources/db/migration/V27__poller_coalescing.sql` — all PR1 schema.
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepo.kt` — the `Schedulable` repo (claim/release/reclaim) + membership + retire queries.
- `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollerMembership.kt` — pure-function watch→poller link maintenance.
- `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/PollerBackfill.kt` — one-time boot backfill (reuses membership).
- Test files mirroring each of the above under `backend/src/test/kotlin/...`.

**Renamed / rewritten:**
- `AvailabilityJobRunRepo.kt` → `AvailabilityRunRepo.kt` (`jobId` → `pollerId`, table `AVAILABILITY_RUN`).
- `AvailabilityPollExecutor.kt` — `handle(poller)`; derive window/cadence; resolve from live watches; retire-on-empty.
- `AvailabilityWatchService.kt` — replace job upsert with membership sync; drop `buildIntent`.

**Deleted:**
- `AvailabilityJobRepo.kt`, `AvailabilityJobIntent.kt`, `WatchScopeResolver.resolve(intent)` overload.

**Modified (small):**
- `backend/build.gradle.kts` — includes allowlist.
- `ResolvedAvailabilityTarget.kt` — add `parentPoiId: Long`.
- `AvailabilityTargetResolver.kt` — capture the parent poi id.
- `Main.kt` — wire `AvailabilityPollerRepo` + new executor + `PollerBackfill`.
- `AvailabilitySnapshotRepo.kt`, `AvailabilityFetchCallRepo.kt` — only the `AVAILABILITY_JOB_RUN`→`AVAILABILITY_RUN` FK ripple (run_id column name unchanged).
- Grafana: `reservable-availability-watch-drill-down.json`, `watch-job-run-detail.json`.

---

## Interfaces (locked signatures used across tasks)

```kotlin
// AvailabilityPollerRepo.kt
data class Poller(
    override val id: Long,
    val provider: String,          // ReservationProviderId.name.lowercase()
    val parentRef: String,         // parentRefKey(providerRef)
    val poiId: Long,               // representative, for coords
    val active: Boolean,
    val nextRunAt: OffsetDateTime,
    val claimedUntil: OffsetDateTime?,
    override val claimToken: String?,
    val lastRunAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) : Schedulable

// membership helpers on AvailabilityPollerRepo
fun upsertActive(provider: String, parentRef: String, poiId: Long, pullNextRunAt: OffsetDateTime?): Long  // returns poller id; revives if dormant
fun linkWatch(watchId: Long, pollerId: Long)
fun replaceLinksForWatch(watchId: Long, pollerIds: Set<Long>)   // insert new, delete stale
fun pollerIdsForWatch(watchId: Long): List<Long>
fun deactivatePollersWithNoLinks(): Int                          // active=false where no watch_poller rows
fun liveWatchesForPoller(pollerId: Long): List<AvailabilityWatchRepo.Watch>
fun retire(pollerId: Long, elapsedWatchIds: List<Long>)          // watches→done, links dropped, poller active=false
// Schedulable:
override fun claimDue(now, limit, leaseDuration): List<Poller>   // WHERE active AND next_run_at<=now
override fun release(id, token, nextRunAt, ranAt): Boolean
override fun reclaimExpired(now): Int

// AvailabilityRunRepo.kt (renamed)
fun start(pollerId: Long, startedAt: OffsetDateTime): Long
fun complete(runId, snapshotCount, completedAt, durationMs): Boolean
fun fail(runId, error, completedAt, durationMs): Boolean
fun countConsecutiveFailures(pollerId: Long): Int

// ResolvedAvailabilityTarget.kt
internal data class ResolvedAvailabilityTarget(
    val reservable: Reservable,
    val provider: ReservationProvider,
    val parentRef: ProviderRef,
    val parentPoiId: Long,          // NEW
    val dateContext: PoiDateContext,
)

// AvailabilityPollerMembership.kt
class AvailabilityPollerMembership(
    private val scopeResolver: WatchScopeResolver,
    private val targets: AvailabilityTargetResolver,
) {
    /** Recompute this watch's poller links from its live target set. Runs inside the
     *  caller's transaction (pass a txn-bound repo). tighterCadencePull = now when the
     *  watch's cadence should pull next_run_at earlier; null otherwise. */
    fun sync(watch: AvailabilityWatchRepo.Watch, repo: AvailabilityPollerRepo, tighterCadencePull: OffsetDateTime?)
}
```

`parentRefKey(ref: ProviderRef): String` moves from `AvailabilityPollExecutor` (private) to a top-level `internal fun` in the `service.availability` package (file `ParentRefKey.kt`) so both membership and the executor use one definition. Values unchanged: RecGov→`recgovId`, Aspira→`mapId.toString()`, ReserveAmerica→`parkId`, ReserveCalifornia→`facilityIds.joinToString(",")`.

---

### Task 1: Schema migration + jOOQ regen

**Files:**
- Create: `backend/src/main/resources/db/migration/V27__poller_coalescing.sql`
- Modify: `backend/build.gradle.kts:175-197`

**Interfaces:**
- Produces: tables `availability_poller`, `availability_watch_poller`; renamed `availability_run` (from `availability_job_run`) with column `poller_id`; drops `availability_job`. jOOQ types `AvailabilityPoller`, `AvailabilityWatchPoller`, `AvailabilityRun` generated under `ca.floo.roadtrip.db.generated.tables`.

- [ ] **Step 1: Write the migration.** Create `V27__poller_coalescing.sql`:

```sql
-- PR1: poller coalescing. The schedulable unit becomes the vendor call unit
-- (provider, parent_ref) instead of one job per watch.

-- 1. Poller: shared schedulable, absorbs availability_job. Stores ONLY scheduling
--    flags; window/cadence/refcount are derived in-run from live watches.
CREATE TABLE availability_poller (
  id             BIGSERIAL   PRIMARY KEY,
  provider       TEXT        NOT NULL,
  parent_ref     TEXT        NOT NULL,
  poi_id         BIGINT      NOT NULL REFERENCES pois(id) ON DELETE CASCADE,
  active         BOOLEAN     NOT NULL DEFAULT TRUE,
  next_run_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  claimed_until  TIMESTAMPTZ,
  claim_token    TEXT,
  last_run_at    TIMESTAMPTZ,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (provider, parent_ref)
);

-- Hot path for the scheduler tick: due + active pollers.
CREATE INDEX availability_poller_due_idx
  ON availability_poller (next_run_at) WHERE active;

-- 2. Coalescing edge: watch N — M poller.
CREATE TABLE availability_watch_poller (
  watch_id   BIGINT NOT NULL REFERENCES availability_watch(id)  ON DELETE CASCADE,
  poller_id  BIGINT NOT NULL REFERENCES availability_poller(id) ON DELETE CASCADE,
  PRIMARY KEY (watch_id, poller_id)
);
CREATE INDEX availability_watch_poller_poller_idx
  ON availability_watch_poller (poller_id);

-- 3. Rename availability_job_run -> availability_run; job_id -> poller_id.
--    Old runs referenced now-dead jobs; drop them (audit history only, tiny counts;
--    snapshots survive via ON DELETE SET NULL on availability_snapshot.run_id).
ALTER TABLE availability_job_run RENAME TO availability_run;
ALTER TABLE availability_run RENAME COLUMN job_id TO poller_id;

DELETE FROM availability_run;  -- orphaned pre-migration runs

ALTER TABLE availability_run
  DROP CONSTRAINT availability_job_run_job_id_fkey;
ALTER TABLE availability_run
  ADD CONSTRAINT availability_run_poller_id_fkey
  FOREIGN KEY (poller_id) REFERENCES availability_poller(id) ON DELETE CASCADE;

-- 4. availability_job is fully replaced by availability_poller + the join.
DROP TABLE availability_job;
```

Note: verify the exact FK constraint name for `availability_job_run.job_id` with `\d availability_job_run` (Postgres default is `availability_job_run_job_id_fkey`; adjust the `DROP CONSTRAINT` if the migration errors). The index `availability_job_run_job_started_idx` auto-follows the table rename but still references column `poller_id` under its old name — rename it for clarity:

```sql
ALTER INDEX availability_job_run_job_started_idx RENAME TO availability_run_poller_started_idx;
```

- [ ] **Step 2: Update the jOOQ includes allowlist.** In `backend/build.gradle.kts`, inside the `includes = listOf(...)` block: replace `"availability_job"` and `"availability_job_run"` with `"availability_poller"`, `"availability_watch_poller"`, and `"availability_run"`. Leave the other entries (including the already-stale `reservable_availability_*`) untouched — out of scope.

- [ ] **Step 3: Regenerate jOOQ + confirm the migration applies.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:generateJooq`
Expected: BUILD SUCCESSFUL; Flyway applies V27 against the codegen DB; generated files `AvailabilityPoller.kt`, `AvailabilityWatchPoller.kt`, `AvailabilityRun.kt` appear under `backend/build/generated/jooq/main/.../tables/`, and `AvailabilityJob.kt` / `AvailabilityJobRun.kt` are gone.

If codegen uses a persistent local DB rather than an ephemeral one, the drop/rename must apply cleanly on top of the existing schema; if it fails on the FK name, fix the `DROP CONSTRAINT` name (Step 1 note) and re-run.

- [ ] **Step 4: Commit.**

```bash
git add backend/src/main/resources/db/migration/V27__poller_coalescing.sql backend/build.gradle.kts
git commit -m "feat(poller): V27 migration — poller + watch_poller tables, rename job_run to run"
```

The repo will not fully compile until Task 6 removes the code referencing the dropped `availability_job`. That is expected; this commit is the schema+codegen checkpoint. (If your workflow requires every commit to compile, fold Tasks 1–6 into one branch and commit at Step 4 of Task 6 instead.)

---

### Task 2: AvailabilityPollerRepo (Schedulable + membership + retire)

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepo.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepoTest.kt`

**Interfaces:**
- Consumes: jOOQ `AVAILABILITY_POLLER`, `AVAILABILITY_WATCH_POLLER`, `AVAILABILITY_WATCH`; framework `Schedulable`, `SchedulableRepo<T>`; `AvailabilityWatchRepo.Watch` + its `fromRecord` (reuse via a shared select).
- Produces: the `Poller` type and all methods in the Interfaces block above.

- [ ] **Step 1: Write the failing test.** Model it on the existing `AvailabilityJobRepoTest` (Testcontainers Postgres, Flyway-migrated). Cover:

```kotlin
class AvailabilityPollerRepoTest : DbTestBase() {   // reuse the project's Testcontainers base
    @Test fun `upsertActive inserts once per provider+parentRef and revives a dormant poller`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()                          // helper: returns poi id
        val id1 = repo.upsertActive("recgov", "232447", poi, pullNextRunAt = null)
        val id2 = repo.upsertActive("recgov", "232447", poi, pullNextRunAt = null)
        assertEquals(id1, id2)                         // UNIQUE(provider,parent_ref)
        repo.deactivatePollersWithNoLinks()            // no links -> dormant
        assertFalse(repo.findById(id1)!!.active)
        val id3 = repo.upsertActive("recgov", "232447", poi, pullNextRunAt = OffsetDateTime.now())
        assertEquals(id1, id3)
        assertTrue(repo.findById(id1)!!.active)         // revived
    }

    @Test fun `claimDue returns only active due pollers and leases them`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val due = repo.upsertActive("recgov", "A", poi, null)
        val notDue = repo.upsertActive("recgov", "B", poi, null)
        repo.parkFar(notDue)                            // test helper via release far-future
        val claimed = repo.claimDue(OffsetDateTime.now(), limit = 10, leaseDuration = Duration.ofMinutes(2))
        assertEquals(listOf(due), claimed.map { it.id })
        assertNotNull(claimed.single().claimToken)
    }

    @Test fun `retire marks watches done, drops links, deactivates poller`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val watch = insertActiveWatch(poiId = poi)      // helper inserts availability_watch row
        val poller = repo.upsertActive("recgov", "A", poi, null)
        repo.linkWatch(watch, poller)
        repo.retire(poller, elapsedWatchIds = listOf(watch))
        assertFalse(repo.findById(poller)!!.active)
        assertTrue(repo.pollerIdsForWatch(watch).isEmpty())
        assertEquals("done", watchStatus(watch))        // helper reads availability_watch.status
    }

    @Test fun `liveWatchesForPoller returns active watches with a future end_date only`() {
        // active + end_date in the future -> included; paused OR fully-elapsed -> excluded
    }
}
```

- [ ] **Step 2: Run the test to verify it fails.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test --tests '*AvailabilityPollerRepoTest*'`
Expected: FAIL — `AvailabilityPollerRepo` unresolved.

- [ ] **Step 3: Implement `AvailabilityPollerRepo`.** Port `claimDue`/`release`/`reclaimExpired` verbatim from `AvailabilityJobRepo` (swapping `AVAILABILITY_JOB`→`AVAILABILITY_POLLER`, `STATUS.eq(ACTIVE)`→`ACTIVE.isTrue`, `FOR UPDATE SKIP LOCKED`). Add membership + retire methods. Key bodies:

```kotlin
class AvailabilityPollerRepo(private val ctx: DSLContext) : SchedulableRepo<AvailabilityPollerRepo.Poller> {

    data class Poller( /* fields per Interfaces block */ ) : Schedulable

    fun upsertActive(provider: String, parentRef: String, poiId: Long, pullNextRunAt: OffsetDateTime?): Long {
        val now = OffsetDateTime.now()
        val insertNextRun = pullNextRunAt ?: now
        return ctx.insertInto(AVAILABILITY_POLLER)
            .set(AVAILABILITY_POLLER.PROVIDER, provider)
            .set(AVAILABILITY_POLLER.PARENT_REF, parentRef)
            .set(AVAILABILITY_POLLER.POI_ID, poiId)
            .set(AVAILABILITY_POLLER.ACTIVE, true)
            .set(AVAILABILITY_POLLER.NEXT_RUN_AT, insertNextRun)
            .onConflict(AVAILABILITY_POLLER.PROVIDER, AVAILABILITY_POLLER.PARENT_REF)
            .doUpdate()
            .set(AVAILABILITY_POLLER.ACTIVE, true)   // revive dormant
            .set(AVAILABILITY_POLLER.POI_ID, poiId)  // refresh representative
            // pull next_run_at earlier only when asked; never push it later
            .set(AVAILABILITY_POLLER.NEXT_RUN_AT,
                 if (pullNextRunAt != null) DSL.least(AVAILABILITY_POLLER.NEXT_RUN_AT, DSL.`val`(pullNextRunAt))
                 else AVAILABILITY_POLLER.NEXT_RUN_AT)
            .set(AVAILABILITY_POLLER.UPDATED_AT, now)
            .returningResult(AVAILABILITY_POLLER.ID)
            .fetchOne()!!.value1()!!
    }

    fun replaceLinksForWatch(watchId: Long, pollerIds: Set<Long>) {
        val existing = pollerIdsForWatch(watchId).toSet()
        (pollerIds - existing).forEach { linkWatch(watchId, it) }
        val stale = existing - pollerIds
        if (stale.isNotEmpty()) {
            ctx.deleteFrom(AVAILABILITY_WATCH_POLLER)
               .where(AVAILABILITY_WATCH_POLLER.WATCH_ID.eq(watchId))
               .and(AVAILABILITY_WATCH_POLLER.POLLER_ID.`in`(stale))
               .execute()
        }
    }

    fun linkWatch(watchId: Long, pollerId: Long) {
        ctx.insertInto(AVAILABILITY_WATCH_POLLER)
           .set(AVAILABILITY_WATCH_POLLER.WATCH_ID, watchId)
           .set(AVAILABILITY_WATCH_POLLER.POLLER_ID, pollerId)
           .onConflictDoNothing()
           .execute()
    }

    fun deactivatePollersWithNoLinks(): Int =
        ctx.update(AVAILABILITY_POLLER)
           .set(AVAILABILITY_POLLER.ACTIVE, false)
           .set(AVAILABILITY_POLLER.UPDATED_AT, OffsetDateTime.now())
           .where(AVAILABILITY_POLLER.ACTIVE.isTrue)
           .andNotExists(
               ctx.selectOne().from(AVAILABILITY_WATCH_POLLER)
                  .where(AVAILABILITY_WATCH_POLLER.POLLER_ID.eq(AVAILABILITY_POLLER.ID)))
           .execute()

    fun liveWatchesForPoller(pollerId: Long): List<AvailabilityWatchRepo.Watch> {
        // active watches linked to this poller whose window still reaches the future.
        // earliestDate is target-local, resolved per-run in the executor; here use
        // end_date >= today (UTC) as a cheap prefilter — the executor's window derive
        // does the exact clamp. Reuse AvailabilityWatchRepo select + fromRecord.
        ...
    }

    fun retire(pollerId: Long, elapsedWatchIds: List<Long>) {
        ctx.transaction { config ->
            val txn = DSL.using(config)
            if (elapsedWatchIds.isNotEmpty()) {
                txn.update(AVAILABILITY_WATCH)
                   .set(AVAILABILITY_WATCH.STATUS, WatchStatus.DONE.wireValue)
                   .set(AVAILABILITY_WATCH.UPDATED_AT, OffsetDateTime.now())
                   .where(AVAILABILITY_WATCH.ID.`in`(elapsedWatchIds))
                   .execute()
            }
            txn.deleteFrom(AVAILABILITY_WATCH_POLLER)
               .where(AVAILABILITY_WATCH_POLLER.POLLER_ID.eq(pollerId)).execute()
            txn.update(AVAILABILITY_POLLER)
               .set(AVAILABILITY_POLLER.ACTIVE, false)
               .set(AVAILABILITY_POLLER.UPDATED_AT, OffsetDateTime.now())
               .where(AVAILABILITY_POLLER.ID.eq(pollerId)).execute()
        }
    }
    // claimDue / release / reclaimExpired ported from AvailabilityJobRepo, ACTIVE.isTrue gate
    // findById, pollerIdsForWatch, fromRecord ...
}
```

- [ ] **Step 4: Run the test to verify it passes.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test --tests '*AvailabilityPollerRepoTest*'`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepo.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepoTest.kt
git commit -m "feat(poller): AvailabilityPollerRepo — schedulable claim/release + membership + retire"
```

---

### Task 3: `parentPoiId` on ResolvedAvailabilityTarget + shared `parentRefKey`

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/ResolvedAvailabilityTarget.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityTargetResolver.kt:41-62`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/ParentRefKey.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/DbAvailabilityTargetResolverTest.kt` (extend if it exists; else add a focused test)

**Interfaces:**
- Produces: `ResolvedAvailabilityTarget.parentPoiId: Long`; top-level `internal fun parentRefKey(ref: ProviderRef): String`.
- Consumes: existing resolver internals (`poiIdsForReservable`, `findProviderRefs`).

- [ ] **Step 1: Write the failing test** asserting the resolved target carries the id of the POI whose provider_ref won, and `parentRefKey` renders each variant:

```kotlin
@Test fun `resolve carries the parent poi id that supplied the provider ref`() {
    // reservable linked to poiA (no provider ref) and poiB (recgov ref);
    // resolve() should pick poiB and set parentPoiId = poiB.id
    val t = resolver.resolve(reservable)!!
    assertEquals(poiB, t.parentPoiId)
    assertEquals("232447", parentRefKey(t.parentRef))
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `./gradlew :backend:test --tests '*DbAvailabilityTargetResolverTest*'` (with JAVA_HOME set)
Expected: FAIL — `parentPoiId` unresolved / `parentRefKey` unresolved.

- [ ] **Step 3: Implement.** Add the field to `ResolvedAvailabilityTarget`. In the resolver, keep the poi id alongside the winning row:

```kotlin
val parent =
    poiIds.asSequence()
        .mapNotNull { poiId -> providerRefsByPoiId[poiId]?.let { poiId to it } }
        .firstOrNull { (_, row) ->
            reservationProviders.forPoi(row) != null && ProviderRefParser.parse(row.providerRefJson) != null
        } ?: return null
val (parentPoiId, parentRow) = parent
val provider = reservationProviders.forPoi(parentRow) ?: return null
val parentRef = ProviderRefParser.parse(parentRow.providerRefJson) ?: return null
return ResolvedAvailabilityTarget(
    reservable = reservable, provider = provider, parentRef = parentRef,
    parentPoiId = parentPoiId,
    dateContext = dateResolver.context(lat = parentRow.lat, lng = parentRow.lng),
)
```

Create `ParentRefKey.kt` with the top-level `internal fun parentRefKey(ref: ProviderRef): String = when (ref) { ... }` (bodies copied verbatim from the executor's current private version).

- [ ] **Step 4: Run to verify it passes.**

Run: `./gradlew :backend:test --tests '*DbAvailabilityTargetResolverTest*'`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/ResolvedAvailabilityTarget.kt backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityTargetResolver.kt backend/src/main/kotlin/ca/floo/roadtrip/service/availability/ParentRefKey.kt backend/src/test/kotlin/ca/floo/roadtrip/service/availability/DbAvailabilityTargetResolverTest.kt
git commit -m "feat(poller): carry parentPoiId on resolved target; extract shared parentRefKey"
```

---

### Task 4: AvailabilityPollerMembership

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollerMembership.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollerMembershipTest.kt`

**Interfaces:**
- Consumes: `WatchScopeResolver.resolve(watch)`, `AvailabilityTargetResolver.resolve(reservable)`, `AvailabilityPollerRepo` (Task 2), `parentRefKey` (Task 3).
- Produces: `sync(watch, repo, tighterCadencePull)`.

- [ ] **Step 1: Write the failing test.** Use fakes for scope+target resolvers (in-memory) and a real `AvailabilityPollerRepo` (Testcontainers), or fake the repo. Cover the spec's regressions:

```kotlin
@Test fun `two watches on same parentRef link to ONE poller`() {
    membership.sync(watchA, repo, null)   // POI-scope, recgov 232447
    membership.sync(watchB, repo, null)   // reservable-scope under same campground
    val a = repo.pollerIdsForWatch(watchA.id)
    val b = repo.pollerIdsForWatch(watchB.id)
    assertEquals(a, b); assertEquals(1, a.size)
}

@Test fun `two POIs sharing a parentRef produce ONE poller`() { /* parentRef-key regression */ }

@Test fun `watch spanning two parentRefs links two pollers`() { /* if target set resolves to 2 distinct keys */ }

@Test fun `re-sync after target change drops the stale link`() { /* replaceLinksForWatch */ }

@Test fun `tighter cadence pull moves next_run_at earlier`() {
    membership.sync(watch, repo, tighterCadencePull = earlier)
    assertTrue(repo.findById(pollerId)!!.nextRunAt <= earlier)
}
```

- [ ] **Step 2: Run to verify it fails.** `./gradlew :backend:test --tests '*AvailabilityPollerMembershipTest*'` → FAIL (unresolved).

- [ ] **Step 3: Implement.**

```kotlin
class AvailabilityPollerMembership(
    private val scopeResolver: WatchScopeResolver,
    private val targets: AvailabilityTargetResolver,
) {
    fun sync(watch: AvailabilityWatchRepo.Watch, repo: AvailabilityPollerRepo, tighterCadencePull: OffsetDateTime?) {
        // Resolve the watch's reservable set -> targets -> distinct (provider, parentRefKey) with a representative poi.
        val resolved = scopeResolver.resolve(watch).mapNotNull { targets.resolve(it) }
        if (watch.status != WatchStatus.ACTIVE) {
            repo.replaceLinksForWatch(watch.id, emptySet())
            repo.deactivatePollersWithNoLinks()
            return
        }
        val keyToPoi = LinkedHashMap<Pair<String, String>, Long>()  // (provider,parentRef) -> repr poi
        for (t in resolved) {
            val key = t.provider.id.name.lowercase() to parentRefKey(t.parentRef)
            keyToPoi.putIfAbsent(key, t.parentPoiId)
        }
        val pollerIds = keyToPoi.entries.map { (key, poi) ->
            repo.upsertActive(provider = key.first, parentRef = key.second, poiId = poi, pullNextRunAt = tighterCadencePull)
        }.toSet()
        repo.replaceLinksForWatch(watch.id, pollerIds)
        repo.deactivatePollersWithNoLinks()   // eager reap of any now-orphaned poller
    }
}
```

- [ ] **Step 4: Run to verify it passes.** Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollerMembership.kt backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityPollerMembershipTest.kt
git commit -m "feat(poller): membership maintenance — watch write recomputes poller links"
```

---

### Task 5: Rename AvailabilityJobRunRepo → AvailabilityRunRepo

**Files:**
- Rename+edit: `AvailabilityJobRunRepo.kt` → `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityRunRepo.kt`
- Modify: `AvailabilitySnapshotRepo.kt`, `AvailabilityFetchCallRepo.kt` (only if they import/compile-reference the generated `AvailabilityJobRun`; `run_id` column is unchanged)
- Test: rename `AvailabilityJobRunRepoTest.kt` → `AvailabilityRunRepoTest.kt`

**Interfaces:**
- Produces: `AvailabilityRunRepo` with `start(pollerId, startedAt)`, `complete`, `fail`, `countConsecutiveFailures(pollerId)`, `Run(id, pollerId, status, snapshotCount, durationMs, error, startedAt, completedAt)`.

- [ ] **Step 1: Update the test first** — rename file/class, `jobId`→`pollerId`, `JOB_ID`→`POLLER_ID`, `AVAILABILITY_JOB_RUN`→`AVAILABILITY_RUN`, and change fixtures to insert an `availability_poller` (not a job) before starting a run.

- [ ] **Step 2: Run to verify it fails.** `./gradlew :backend:test --tests '*AvailabilityRunRepoTest*'` → FAIL (unresolved / old type).

- [ ] **Step 3: Implement the rename.** Copy `AvailabilityJobRunRepo.kt` to `AvailabilityRunRepo.kt`; replace `AVAILABILITY_JOB_RUN`→`AVAILABILITY_RUN`, `.JOB_ID`→`.POLLER_ID`, `jobId`→`pollerId` throughout; rename `class`/`data class Run`. Delete the old file. Fix any import of the generated `AvailabilityJobRun` in the snapshot/fetch-call repos (their `run_id` FK now points at `availability_run` — column reference is unchanged, only the table type name in any explicit reference changes; most references are via `AVAILABILITY_SNAPSHOT.RUN_ID` and need no change).

- [ ] **Step 4: Run to verify it passes.** Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityRunRepo.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityRunRepoTest.kt
git rm backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRunRepo.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRunRepoTest.kt
git commit -m "refactor(poller): rename AvailabilityJobRunRepo to AvailabilityRunRepo (job_id -> poller_id)"
```

---

### Task 6: Rewrite the executor + cut over the service, Main, and delete dead code

This is the atomic cutover. After it, the project compiles again with pollers as the only schedulable.

**Files:**
- Rewrite: `AvailabilityPollExecutor.kt`
- Rewrite: `AvailabilityWatchService.kt`
- Modify: `Main.kt:233-254`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/PollerBackfill.kt`
- Delete: `AvailabilityJobRepo.kt`, `AvailabilityJobIntent.kt`, and `WatchScopeResolver.resolve(intent)` overload
- Test: `AvailabilityPollExecutorTest.kt` (rewrite), `AvailabilityWatchServiceTest.kt` (update), `PollerBackfillTest.kt` (new)

**Interfaces:**
- Consumes: `AvailabilityPollerRepo.Poller`, `AvailabilityRunRepo`, `AvailabilityPollerMembership`, `CatalogAvailabilityBatcher.fetchByGroup`, `AvailabilityDateResolver.resolvePollingWindow`, `WatchScopeResolver.resolve(watch)`.

- [ ] **Step 1: Write the failing executor test.** Fakes for repo/batcher/resolvers. Cover the spec's executor regressions:

```kotlin
@Test fun `two live watches on one poller -> one fetch over the union window`() {
    // watchA nights 07-06..07-08, watchB 07-01..07-03, same campground.
    // Expect batcher called with ONE group; window = 07-01..07-08 (clamped to earliest).
}
@Test fun `cadence is the min over live watches`() {
    // watchA cadence 300, watchB cadence 30 -> nextRunAt ~ now+30s on success.
}
@Test fun `empty window retires the poller and does not fetch`() {
    // all watches fully elapsed -> batcher never called; repo.retire(...) invoked;
    // returned HandlerResult.nextRunAt is inert (poller now inactive).
}
@Test fun `failure backs off using derived cadence and consecutive failures`() { ... }
@Test fun `snapshot writing is unchanged (one row per observation)`() { ... }
```

- [ ] **Step 2: Run to verify it fails.** Expected: FAIL (compile / behavior).

- [ ] **Step 3: Rewrite `AvailabilityPollExecutor`.**

```kotlin
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
            val elapsed = pollers.pollerIdsForWatch  // (see note) -> gather this poller's linked watch ids
            pollers.retire(poller.id, elapsedWatchIds = linkedWatchIds(poller.id))
            return HandlerResult(nextRunAt = OffsetDateTime.now())  // inert; poller now inactive
        }

        val cadenceSec = liveWatches.minOf { it.cadenceSec }.coerceAtLeast(1).let {
            // fall-through ready for PR4; PR1 cadence_sec is NOT NULL so this is just min.
            if (it <= 0) GLOBAL_DEFAULT_SEC else it
        }

        val startedAt = OffsetDateTime.now()
        val runId = runs.start(poller.id, startedAt)
        var runFailed = false
        var madeCall = false
        try {
            withContext(MDCContext(mapOf("run_id" to runId.toString()))) {
                val resolved = liveWatches.flatMap { w -> scopeResolver.resolve(w).mapNotNull { targets.resolve(it) } }
                    .filter { parentRefKey(it.parentRef) == poller.parentRef && it.provider.id.name.lowercase() == poller.provider }
                val results = batcher.fetchByGroup(
                    targets = resolved,
                    windowFor = { context, caps ->
                        dateResolver.resolvePollingWindow(
                            startDate = minStart, endDate = maxEnd, context = context,
                            bookingHorizonDays = caps.bookingHorizonDays, maxDays = MAX_POLL_WINDOW_DAYS,
                        )
                    },
                    fetch = { parentRef, provider, rows, window ->
                        provider.catalogAvailability(CatalogAvailabilityRequest(
                            ref = parentRef, reservables = rows.map { it.toCatalogReservableRef() },
                            startDate = window.startDate, endDate = window.endDate, force = true,
                        ))
                    },
                )
                madeCall = results.any { it.window != null }
                val failure = results.firstOrNull { it.outcome != FetchOutcome.OK }
                val snapshotCount = results.sumOf { appendSnapshots(it, runId) }  // UNCHANGED helper
                recordFetchCalls(results, runId)                                  // UNCHANGED helper
                val completedAt = OffsetDateTime.now()
                val durationMs = durationMs(startedAt, completedAt)
                if (failure != null) { runFailed = true; runs.fail(runId, failure.outcome.name.lowercase(), completedAt, durationMs) }
                else { runs.complete(runId, snapshotCount, completedAt, durationMs) }
            }
        } catch (e: Exception) {
            log.warn("poller {} run {} failed: {}", poller.id, runId, e.message)
            runFailed = true
            val completedAt = OffsetDateTime.now()
            runs.fail(runId, e.message ?: e::class.simpleName ?: "unknown", completedAt, durationMs(startedAt, completedAt))
        }

        val nextRunAt = if (runFailed) {
            val failures = runs.countConsecutiveFailures(poller.id)
            val backoffSec = (cadenceSec * Math.pow(BACKOFF_BASE_MULTIPLIER, failures.toDouble())).toLong()
            OffsetDateTime.now().plusSeconds(backoffSec.coerceAtMost(BACKOFF_CEILING_SEC))
        } else {
            OffsetDateTime.now().plusSeconds(cadenceSec.toLong())
        }
        return HandlerResult(nextRunAt = nextRunAt)
    }

    private fun linkedWatchIds(pollerId: Long): List<Long> = pollers.watchIdsForPoller(pollerId)  // add to repo
    // appendSnapshots / recordFetchCalls / durationMs unchanged (parentRefKey now the shared top-level fun)
}
private const val MAX_POLL_WINDOW_DAYS = 60
private const val BACKOFF_BASE_MULTIPLIER = 2.0
private const val BACKOFF_CEILING_SEC = 3_600L
private const val GLOBAL_DEFAULT_SEC = 300      // 5 min fall-through; PR4 layers poi override
```

Add `fun watchIdsForPoller(pollerId: Long): List<Long>` to `AvailabilityPollerRepo` (all linked watch ids, for the retire path — retire marks them done since an empty window means all are elapsed).

- [ ] **Step 4: Rewrite `AvailabilityWatchService`** to sync membership instead of jobs:

```kotlin
class AvailabilityWatchService(
    private val ctx: DSLContext,
    private val reservablesRepo: ReservableRepo,
    private val membershipFor: (DSLContext) -> AvailabilityPollerMembership,  // txn-bound factory
) {
    fun create(input: AvailabilityWatchRepo.CreateInput): Watch =
        ctx.transactionResult { config ->
            val txn = DSL.using(config)
            val watch = AvailabilityWatchRepo(txn).create(input)
            membershipFor(txn).sync(watch, AvailabilityPollerRepo(txn), tighterCadencePull = OffsetDateTime.now())
            watch
        }

    fun update(id: Long, input: AvailabilityWatchRepo.UpdateInput): Watch? =
        ctx.transactionResult { config ->
            val txn = DSL.using(config)
            val updated = AvailabilityWatchRepo(txn).update(id, input) ?: return@transactionResult null
            // A cadence tighten should pull next_run_at; simplest correct: always allow a pull to now
            // when active (governor gates the actual fetch in PR4).
            val pull = if (updated.status == WatchStatus.ACTIVE) OffsetDateTime.now() else null
            membershipFor(txn).sync(updated, AvailabilityPollerRepo(txn), tighterCadencePull = pull)
            updated
        }

    fun delete(id: Long): Boolean =
        ctx.transactionResult { config ->
            val txn = DSL.using(config)
            val deleted = AvailabilityWatchRepo(txn).delete(id)   // cascade drops watch_poller links
            AvailabilityPollerRepo(txn).deactivatePollersWithNoLinks()
            deleted
        }
}
```

Delete `buildIntent`. (Membership reads the watch's own status; a paused/done watch drops its links.)

- [ ] **Step 5: Create `PollerBackfill`** — one-time, idempotent boot backfill that fills poller links for any active watch that has none:

```kotlin
class PollerBackfill(
    private val ctx: DSLContext,
    private val membership: AvailabilityPollerMembership,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    fun run() {
        val watchRepo = AvailabilityWatchRepo(ctx)
        val pollerRepo = AvailabilityPollerRepo(ctx)
        val active = watchRepo.list(status = WatchStatus.ACTIVE, limit = 500)
        var filled = 0
        for (w in active) {
            if (pollerRepo.pollerIdsForWatch(w.id).isNotEmpty()) continue
            ctx.transaction { c -> AvailabilityPollerMembership(...).sync(w, AvailabilityPollerRepo(DSL.using(c)), OffsetDateTime.now()) }
            filled++
        }
        if (filled > 0) log.info("poller backfill linked {} active watches", filled)
    }
}
```

- [ ] **Step 6: Rewire `Main.kt`.** Replace the `availability_job` wiring:

```kotlin
val pollerRepo = AvailabilityPollerRepo(ctx)
val membership = AvailabilityPollerMembership(WatchScopeResolver(reservablesRepo), availabilityTargets)
PollerBackfill(ctx, membership).run()   // after Flyway, before scheduler start
val pollExecutor = AvailabilityPollExecutor(
    pollers = pollerRepo, reservablesRepo = reservablesRepo, batcher = CatalogAvailabilityBatcher(),
    snapshots = availabilitySnapshots, runs = AvailabilityRunRepo(ctx),
    dateResolver = availabilityDateResolver, targets = availabilityTargets, fetchCalls = AvailabilityFetchCallRepo(ctx),
)
val availabilityScheduler = Scheduler(repo = pollerRepo, handler = pollExecutor::handle, name = "availability")
availabilityScheduler.start(schedulerScope)
```

Also wire `AvailabilityWatchService` with the `membershipFor` factory. Delete `AvailabilityJobRepo.kt`, `AvailabilityJobIntent.kt`, and the `WatchScopeResolver.resolve(intent)` overload (keep `resolve(watch)`).

- [ ] **Step 7: Build + run the full backend test suite.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test`
Expected: BUILD SUCCESSFUL, all tests green (executor, membership, service, repo, backfill).

- [ ] **Step 8: Commit.**

```bash
git add -A
git commit -m "feat(poller): executor handles pollers; service syncs membership; boot backfill; drop availability_job"
```

---

### Task 7: Grafana rework

**Files:**
- Modify: `grafana/dashboards/reservable-availability-watch-drill-down.json`
- Modify: `grafana/dashboards/watch-job-run-detail.json`

**Interfaces:** panel SQL now reads `availability_watch → availability_watch_poller → availability_poller → availability_run`. `window`/`cadence`/`refcount` are computed in-panel (no longer columns).

- [ ] **Step 1: Update the drill-down dashboard.**
  - Template var `job_id` → `poller_id`: `SELECT id::text FROM availability_poller WHERE ${watch_id:sqlstring} IN ('', '__all') OR id IN (SELECT poller_id FROM availability_watch_poller WHERE watch_id::text IN (${watch_id:sqlstring}))`.
  - `run_id` var: `SELECT r.id::text FROM availability_run r JOIN availability_poller p ON p.id = r.poller_id WHERE ...`.
  - "Queue Summary": read `availability_poller` (`active`, `next_run_at`, `claimed_until`, `last_run_at`); "active/dormant" instead of active/paused/done.
  - "Watches & Jobs" panel → **"Watches & Pollers"**: `SELECT w.id AS watch_id, p.id AS poller_id, p.provider, p.parent_ref, COUNT(*) OVER (PARTITION BY p.id) AS attached_watches FROM availability_watch w JOIN availability_watch_poller wp ON wp.watch_id=w.id JOIN availability_poller p ON p.id=wp.poller_id`. Derive `cadence` = `MIN(w.cadence_sec)` per poller, `refcount` = attached-watch count, `window` = `MIN(w.start_date)..MAX(w.end_date)` in the query.
  - "Recent Job Runs" → **"Recent Runs"**: `FROM availability_run r JOIN availability_poller p ON p.id=r.poller_id`, expose `poller_id`.
  - "Fetch calls for this run": swap the `availability_job_run jr JOIN availability_job j` join for `availability_run r`, filter on `r.poller_id`.
  - Panel navigation links: `var-job_id` → `var-poller_id`.

- [ ] **Step 2: Update `watch-job-run-detail.json`** "Run properties": `SELECT * FROM availability_run WHERE id = NULLIF(${run_id:sqlstring}, '')::bigint`. Rename the dashboard title/uid references from job to poller where they appear as labels (leave the file name to avoid churn, matching the spec's convention).

- [ ] **Step 3: Validate JSON.**

Run: `python3 -m json.tool grafana/dashboards/reservable-availability-watch-drill-down.json > /dev/null && python3 -m json.tool grafana/dashboards/watch-job-run-detail.json > /dev/null && echo OK`
Expected: `OK`.

- [ ] **Step 4: Commit.**

```bash
git add grafana/dashboards/reservable-availability-watch-drill-down.json grafana/dashboards/watch-job-run-detail.json
git commit -m "feat(poller): Grafana drill-down reworked to watch -> poller -> run"
```

---

### Task 8: End-to-end verification (the Problem regression)

**Files:** none (verification only). Uses the Tilt dev stack (`tilt up` — postgres + backend).

- [ ] **Step 1: Bring up the stack.** `tilt up` (per project convention; prefer over `make run`). Wait for backend healthy.

- [ ] **Step 2: Create the two Problem watches** via the API: watch 1 = whole POI 2006 (Upper Pines) nights 07-06..07-08; watch 2 = one reservable under it, nights 07-01..07-03.

- [ ] **Step 3: Confirm coalescing in Postgres.**

Run: `SELECT provider, parent_ref, COUNT(*) FROM availability_watch_poller wp JOIN availability_poller p ON p.id=wp.poller_id GROUP BY 1,2;`
Expected: **one** poller row for Upper Pines with **two** attached watches.

- [ ] **Step 4: Confirm one call per run.** After a tick, check `availability_fetch_call` for the latest run:

Run: `SELECT r.poller_id, r.id run, COUNT(fc.*) calls FROM availability_run r LEFT JOIN availability_fetch_call fc ON fc.run_id=r.id GROUP BY 1,2 ORDER BY r.id DESC LIMIT 5;`
Expected: **one** fetch call per run for the Upper Pines poller (was two calls across two jobs before).

- [ ] **Step 5: Confirm expiry retires.** Fast-forward / use a watch whose dates are all in the past → confirm the poller flips `active=false` and stops producing runs, and its watches flip to `done`.

- [ ] **Step 6: Record the evidence** (screenshots of the reworked "Watches & Pollers" panel + the two SQL results) in the PR description.

---

## Self-Review

**Spec coverage (PR1 bullet: "New poller/join tables (parentRef key), executor takes a poller and derives window/cadence, single-scope membership, migration, Grafana rework, min cadence + GLOBAL_DEFAULT + existing backoff. Snapshot writing unchanged."):**
- poller/join tables (parentRef key) → Task 1 (schema) + Task 2 (repo). ✓
- executor takes a poller, derives window/cadence → Task 6. ✓
- single-scope membership → Task 4 (watches stay single-scope; membership resolves the one scope). ✓
- migration (rename run, drop job, backfill) → Task 1 (DDL) + Task 6 Step 5 (Kotlin backfill). ✓
- Grafana rework → Task 7. ✓
- min cadence + GLOBAL_DEFAULT + existing backoff → Task 6 Step 3. ✓
- snapshot writing unchanged → Task 6 keeps `appendSnapshots` verbatim. ✓
- Lifecycle "tick is the reaper / retire-on-empty" → Task 2 `retire` + Task 6 empty-window branch. ✓

**Spec testing regressions mapped:** two watches/one parentRef → one poller/one call (Task 4 + Task 8); two POIs sharing parentRef → one poller (Task 4); watch spanning two parentRefs (Task 4); cadence min (Task 6); expiry retire/clamp (Task 6, Task 8). Governor, force-pull, cube, cadence config → deferred to PR3/PR4/PR5 (out of PR1 scope). ✓

**Deliberately out of PR1 scope (documented):** `pois.cadence_override_sec`, Bucket4j governor (PR4); `availability_cell` + edge-triggered snapshots (PR3); force pull (PR5); watch-as-set multi-target (PR2).

**Open verification during execution:** (1) exact FK constraint name in the V27 `DROP CONSTRAINT` (Task 1 note); (2) whether `AvailabilitySnapshotRepo`/`AvailabilityFetchCallRepo` reference the generated `AvailabilityJobRun` type by name (Task 5) — column refs need no change, only an explicit type import would; (3) `liveWatchesForPoller` earliest-date prefilter uses UTC `today` as a cheap gate, with the executor's `resolvePollingWindow` doing the exact target-local clamp.
